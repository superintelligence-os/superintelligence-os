package com.sios.cortafuegos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.NotificationCompat
import com.sios.R
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/*
 * El cortafuegos por dentro: una VPN local que solo lleva el DNS. Nada sale a ningún servidor de VPN.
 *
 * - El túnel tiene una sola ruta: nuestra IP de DNS falsa (10.111.222.53 y fd6b:6366:7366::53), que
 *   es el DNS de la VPN. El resto del tráfico de las apps no entra en el túnel: sale por la red de
 *   siempre, con su velocidad y su batería de siempre. Por eso no hace falta tun2socks ni una pila
 *   TCP/IP propia (que solo tendría sentido para reenviar todo el tráfico).
 * - Cada consulta DNS que llega se atribuye a su app con ConnectivityManager.getConnectionOwnerUid
 *   (lo permite al dueño de la VPN; desde Android 10 el resolutor del sistema pone a su socket el uid
 *   de la app que pregunta). Con eso se decide: si la app no tiene internet en esta red (wifi o
 *   datos), NXDOMAIN para todo; si el dominio está en la lista de publicidad y rastreadores (y no lo
 *   ha permitido el usuario), NXDOMAIN; si no, se pregunta al DNS de la red real con un socket
 *   protegido (protect + bindSocket) y se devuelve la respuesta.
 * - Las respuestas vuelven al sistema con TTL de 1 s y las negativas sin SOA: la caché DNS del sistema
 *   es una por red y la comparten todas las apps, y si guardara mucho, lo que pidió una app le valdría
 *   a otra (una app sin red resolvería con lo que pidió otra, y el registro por app saldría cojo).
 *   Para no ir a la red cada vez, la caché de verdad (con el TTL real) es la nuestra.
 * - ¿Por qué no bloquear las apps solo con addAllowedApplication/addDisallowedApplication, como
 *   NetGuard? Porque las rutas son de toda la VPN: para tirar el tráfico de una app habría que meter
 *   todo (0.0.0.0/0) en el túnel, y entonces el de las apps permitidas también, que habría que
 *   reenviar con una pila TCP/IP propia. Así que el bloqueo por app es por DNS: sin nombres, la app no
 *   llega a ningún servidor. Límite conocido: una app que conecte a una IP fija, sin preguntar el
 *   nombre (algunas de mensajería lo hacen como plan B), no se corta.
 * - addDisallowedApplication sí se usa, para lo contrario: dejar fuera del túnel a la propia SIOS (que
 *   hace de filtro y no debe filtrarse a sí misma) y a las apps esenciales (Termux con el agente,
 *   Google Play, telefonía, el sistema) mientras el usuario no les quite la red. Si el cortafuegos
 *   fallara, ellas no lo notarían. El servicio de Termux en 127.0.0.1 no pasa nunca por el túnel.
 * - Si el proceso muere, el túnel se cierra y el móvil vuelve al DNS de siempre: el fallo deja
 *   internet, no lo quita (salvo con «Bloquear conexiones sin VPN», del que se avisa).
 * - Convive con «VPN siempre activa» (el sistema lo arranca y se respeta). Si otra VPN se conecta,
 *   Android cierra esta (onRevoke): se apaga, se avisa y no se pelea por volver.
 */
class CortafuegosVpn : VpnService() {
    companion object {
        /** Grande para que quepan respuestas DNS largas (por el túnel solo pasa DNS). */
        const val MTU = 16384
        const val EXTRA_PRIMER_PLANO = "primer_plano"
        private const val DIR4 = "10.111.222.1"
        private const val DNS4 = "10.111.222.53"
        private const val DIR6 = "fd6b:6366:7366::1"
        private const val DNS6 = "fd6b:6366:7366::53"
        /** TTL que ve el sistema (ver arriba). */
        private const val TTL_SISTEMA = 1L
        private const val CANAL = "cortafuegos"
        private const val AVISO_ARRANQUE = 7301
        private const val AVISO_APAGADO = 7302

        @Volatile var instancia: CortafuegosVpn? = null
            private set
    }

    /** El túnel está levantado. */
    @Volatile var levantado = false
        private set

    private var tunel: ParcelFileDescriptor? = null
    @Volatile private var generacion = 0
    private var excluidas: Set<String> = emptySet()
    private var enPrimerPlano = false
    private var reintentos = 0
    private val escritura = Any()

    // Un hilo para encender, apagar y el reloj; varios para contestar (cada consulta puede esperar a la red).
    private val control = Executors.newSingleThreadScheduledExecutor { Thread(it, "cortafuegos") }
    private val trabajo = ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(512), ThreadPoolExecutor.DiscardOldestPolicy())

    // La red de verdad (la app va fuera del túnel, así que su red por defecto es la del móvil).
    @Volatile private var red: Network? = null
    @Volatile private var servidores: List<InetAddress> = emptyList()
    @Volatile private var tipo: Cortafuegos.Red? = null
    @Volatile private var dnsPrivado: String? = null

    private class Guardada(val respuesta: ByteArray, val caduca: Long)
    private val cache = object : LinkedHashMap<String, Guardada>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Guardada>?) = size > 3000
    }
    private val paquetes = ConcurrentHashMap<Int, String>()
    private lateinit var cm: ConnectivityManager

    private val vigilante = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network != red) synchronized(cache) { cache.clear() } // otra red, otras respuestas
            red = network
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            red = network
            tipo = Cortafuegos.redDe(caps)
            publicar()
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            red = network
            // Por si acaso: nunca nuestro propio DNS falso.
            servidores = lp.dnsServers.filterNot { it.hostAddress in setOf("10.111.222.53", "fd6b:6366:7366::53") }
            dnsPrivado = lp.privateDnsServerName
            publicar()
        }

        override fun onLost(network: Network) {
            if (network != red) return
            red = null
            servidores = emptyList()
            tipo = null
            publicar()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instancia = this
        cm = getSystemService(ConnectivityManager::class.java)
        // La mejor red que NO sea una VPN. Con la red por defecto no vale: en cuanto se levanta el túnel,
        // el sistema la da como la de la propia VPN y el DNS «real» acababa siendo el nuestro (bucle).
        runCatching {
            val peticion = android.net.NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerBestMatchingNetworkCallback(peticion, vigilante, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        var vueltas = 0
        control.scheduleWithFixedDelay({
            runCatching { Registro.tic(this) }
            // «Siempre activa» y «Bloquear conexiones sin VPN» pueden cambiar en los ajustes del sistema.
            if (++vueltas % 5 == 0 && levantado) publicar()
        }, 2, 2, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_PRIMER_PLANO, false) == true) primerPlano()
        // Lo arranca el sistema por «VPN siempre activa»: el usuario lo ha elegido así.
        if (intent?.action == SERVICE_INTERFACE) Cortafuegos.prefs(this).edit().putBoolean("activo", true).apply()
        if (!Cortafuegos.quiereActivo(this)) {
            apagar()
            return START_NOT_STICKY
        }
        Cortafuegos.ponerEstado(this) { it.copy(arrancando = !levantado, error = false, revocado = false) }
        runCatching { control.execute { levantar() } }
        return START_STICKY
    }

    /** Monta (o vuelve a montar, con otras apps fuera) el túnel. En el hilo de control. */
    private fun levantar() {
        if (!Cortafuegos.quiereActivo(this)) return
        try {
            Listas.cargar(this)
            val fuera = Cortafuegos.fuera(this)
            val b = Builder()
                .setSession(getString(R.string.cf_titulo))
                .setMtu(MTU)
                .addAddress(DIR4, 32)
                .addAddress(DIR6, 128)
                .addDnsServer(DNS4)
                .addDnsServer(DNS6)
                .addRoute(DNS4, 32)
                .addRoute(DNS6, 128)
                // Sin esto, Android cortaría el IPv4/IPv6 que no va por el túnel: que siga por la red real.
                .allowFamily(OsConstants.AF_INET)
                .allowFamily(OsConstants.AF_INET6)
                .setBlocking(false)
                // Que las apps vean la red de verdad: medida o no, como la wifi o los datos de debajo.
                .setMetered(false)
                .setConfigureIntent(
                    PendingIntent.getActivity(this, 0, Intent(this, CortafuegosActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
                )
            for (p in fuera) runCatching { b.addDisallowedApplication(p) }
            val nuevo = b.establish() ?: throw IllegalStateException("sin permiso de VPN")
            val viejo = tunel
            tunel = nuevo
            excluidas = fuera
            val gen = ++generacion
            leer(nuevo, gen)
            runCatching { viejo?.close() }
            levantado = true
            reintentos = 0
            publicar()
            Cortafuegos.ponerEstado(this) { it.copy(encendido = true, arrancando = false, error = false, revocado = false) }
            if (enPrimerPlano) { stopForeground(STOP_FOREGROUND_REMOVE); enPrimerPlano = false }
        } catch (e: Exception) {
            android.util.Log.w("Cortafuegos", "no se pudo levantar el túnel", e)
            cerrarTunel()
            Cortafuegos.ponerEstado(this) { it.copy(encendido = false, arrancando = false, error = true) }
            stopSelf()
        }
    }

    /** Cambiaron las reglas: si cambian las apps que van fuera del túnel, se vuelve a montar. */
    internal fun reglasCambiadas() = runCatching {
        control.execute { if (levantado && Cortafuegos.fuera(this) != excluidas) levantar() }
    }

    /** Apagar a petición del usuario. */
    internal fun apagar() = runCatching {
        control.execute {
            cerrarTunel()
            Cortafuegos.ponerEstado(this) { it.copy(encendido = false, arrancando = false) }
            stopSelf()
        }
    }

    private fun cerrarTunel() {
        levantado = false
        generacion++
        runCatching { tunel?.close() }
        tunel = null
        runCatching { Registro.guardar(this) }
    }

    /** Otra VPN se ha conectado (o se ha desconectado desde los ajustes): se apaga y se avisa. */
    override fun onRevoke() {
        Cortafuegos.prefs(this).edit().putBoolean("activo", false).apply()
        Cortafuegos.ponerEstado(this) { it.copy(revocado = true) }
        avisarApagado()
        apagar()
    }

    override fun onDestroy() {
        instancia = null
        runCatching { cm.unregisterNetworkCallback(vigilante) }
        cerrarTunel()
        control.shutdown()
        trabajo.shutdown()
        Cortafuegos.ponerEstado(this) { it.copy(encendido = false, arrancando = false) }
        super.onDestroy()
    }

    /** Lo que ve la pantalla del cortafuegos: la red, «siempre activa», el DNS privado… */
    private fun publicar() {
        val siempre = runCatching { isAlwaysOn }.getOrDefault(false)
        val bloqueo = runCatching { isLockdownEnabled }.getOrDefault(false)
        Cortafuegos.ponerEstado(this) { it.copy(red = tipo, dnsPrivado = dnsPrivado, siempreActiva = siempre, bloqueoSinVpn = bloqueo) }
    }

    // ——— El túnel ———

    /** Lee del túnel en su propio hilo; cada consulta se contesta en el grupo de trabajo. */
    private fun leer(pfd: ParcelFileDescriptor, gen: Int) = Thread({
        val fd = pfd.fileDescriptor
        val buf = ByteArray(MTU)
        val sondeo = arrayOf(StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() })
        var roto = false
        while (gen == generacion) {
            try {
                sondeo[0].revents = 0
                // Con espera limitada: así el hilo ve enseguida que se ha apagado (un read bloqueado no se enteraría).
                if (Os.poll(sondeo, 1000) <= 0) continue
                if (sondeo[0].revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) { roto = true; break }
                val n = Os.read(fd, buf, 0, buf.size)
                if (n <= 0) continue
                // Solo UDP al puerto 53 (lo demás, como las pruebas de DNS cifrado al 853, se descarta).
                val c = Paquetes.leer(buf, n) ?: continue
                if (c.puertoDestino != 53) continue
                trabajo.execute { runCatching { atender(c, pfd, gen) } }
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue
                roto = true
                break
            } catch (_: Exception) {
                continue
            }
        }
        // Se ha roto el túnel sin apagarlo: un reintento a los pocos segundos (no más, para no entrar en bucle).
        if (roto && gen == generacion && Cortafuegos.quiereActivo(this) && reintentos++ < 2) {
            runCatching { control.schedule({ if (gen == generacion) levantar() }, 3, TimeUnit.SECONDS) }
        }
    }, "cortafuegos-tunel").apply { isDaemon = true }.start()

    private fun escribir(pfd: ParcelFileDescriptor, gen: Int, paquete: ByteArray) {
        synchronized(escritura) {
            if (gen != generacion) return
            runCatching { Os.write(pfd.fileDescriptor, paquete, 0, paquete.size) }
        }
    }

    /** Nombres de la red local y búsquedas inversas: se resuelven sin más y no se apuntan. */
    private fun esLocal(n: String) =
        '.' !in n || n.endsWith(".arpa") || n.endsWith(".local") || n.endsWith(".lan") || n.endsWith(".internal") || n == "localhost"

    private fun atender(c: Consulta, pfd: ParcelFileDescriptor, gen: Int) {
        val p = Dns.pregunta(c.dns) ?: return
        val uid = propietario(c)
        val r = Cortafuegos.reglas(this)
        val t = tipo
        val sinRed = uid >= 0 && t != null && uid in (if (t == Cortafuegos.Red.WIFI) r.uidSinWifi else r.uidSinDatos)
        val local = esLocal(p.nombre)
        val motivo = when {
            sinRed -> Registro.SIN_RED
            local || Listas.permitido(this, p.nombre) -> Registro.PASA
            Listas.bloqueado(this, p.nombre) -> Registro.LISTA
            else -> Registro.PASA
        }
        val respuesta = if (motivo != Registro.PASA) Dns.negativa(c.dns, p, Dns.NXDOMAIN) else resolver(c.dns, p)
        escribir(pfd, gen, Paquetes.respuesta(c, respuesta))
        if (!local) Registro.apuntar(this, paqueteDe(uid), p.nombre, motivo)
    }

    /** De qué app es la consulta (uid), o -1 si el sistema no lo sabe. */
    private fun propietario(c: Consulta): Int = runCatching {
        cm.getConnectionOwnerUid(
            OsConstants.IPPROTO_UDP,
            InetSocketAddress(InetAddress.getByAddress(c.origen), c.puertoOrigen),
            InetSocketAddress(InetAddress.getByAddress(c.destino), c.puertoDestino),
        )
    }.getOrDefault(-1)

    private fun paqueteDe(uid: Int): String {
        if (uid < 0) return ""
        return paquetes.getOrPut(uid) {
            packageManager.getPackagesForUid(uid)?.sorted()?.firstOrNull() ?: packageManager.getNameForUid(uid) ?: ""
        }
    }

    /** La respuesta de verdad: de nuestra caché o del DNS de la red. */
    private fun resolver(consulta: ByteArray, p: Dns.Pregunta): ByteArray {
        val clave = "${p.nombre}/${p.tipo}/${p.clase}"
        val ahora = SystemClock.elapsedRealtime()
        val guardada = synchronized(cache) { cache[clave] }
        val r = if (guardada != null && guardada.caduca > ahora) Dns.conId(guardada.respuesta, Dns.id(consulta))
        else {
            val fuera = preguntarFuera(consulta) ?: return Dns.negativa(consulta, p, Dns.SERVFAIL)
            val rc = Dns.rcode(fuera)
            if (rc == 0 || rc == Dns.NXDOMAIN) {
                val ttl = Dns.ttl(fuera).coerceIn(0, 3600)
                if (ttl > 0) synchronized(cache) { cache[clave] = Guardada(fuera, ahora + ttl * 1000) }
            }
            fuera
        }
        val salida = Dns.limitarTtl(r, TTL_SISTEMA)
        return if (salida.size > Paquetes.MAX_DNS) Dns.truncar(salida, p) else salida
    }

    /** Pregunta al DNS de la red real (por UDP y, si la respuesta no cabe, por TCP). */
    private fun preguntarFuera(consulta: ByteArray): ByteArray? {
        val n = red
        for (s in servidores.take(3)) {
            val r = runCatching {
                DatagramSocket().use { ds ->
                    protect(ds)
                    n?.let { runCatching { it.bindSocket(ds) } }
                    ds.soTimeout = 2500
                    ds.connect(InetSocketAddress(s, 53))
                    ds.send(DatagramPacket(consulta, consulta.size))
                    val buf = ByteArray(65535)
                    var respuesta: ByteArray? = null
                    var intentos = 0
                    // Lo que no lleve nuestro identificador (respuestas tardías) se ignora.
                    while (respuesta == null && intentos++ < 3) {
                        val pk = DatagramPacket(buf, buf.size)
                        ds.receive(pk)
                        if (pk.length >= 12 && Dns.id(buf) == Dns.id(consulta)) respuesta = buf.copyOf(pk.length)
                    }
                    respuesta
                }
            }.getOrNull() ?: continue
            return if (Dns.truncada(r)) porTcp(consulta, s, n) ?: r else r
        }
        return null
    }

    private fun porTcp(consulta: ByteArray, s: InetAddress, n: Network?): ByteArray? = runCatching {
        Socket().use { so ->
            protect(so)
            n?.let { runCatching { it.bindSocket(so) } }
            so.soTimeout = 3000
            so.connect(InetSocketAddress(s, 53), 3000)
            val salida = DataOutputStream(so.getOutputStream())
            salida.writeShort(consulta.size)
            salida.write(consulta)
            salida.flush()
            val entrada = DataInputStream(so.getInputStream())
            ByteArray(entrada.readUnsignedShort()).also { entrada.readFully(it) }
        }
    }.getOrNull()

    // ——— Avisos ———

    private fun canal() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, getString(R.string.cf_canal), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun abrir() = PendingIntent.getActivity(
        this, 1, Intent(this, CortafuegosActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Arrancado desde segundo plano: en primer plano hasta que el túnel esté listo (unos segundos). */
    private fun primerPlano() {
        canal()
        val n = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_cortafuegos)
            .setContentTitle(getString(R.string.cf_encendiendo))
            .setContentIntent(abrir())
            .setSilent(true)
            .build()
        enPrimerPlano = runCatching { startForeground(AVISO_ARRANQUE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED) }.isSuccess
    }

    private fun avisarApagado() = runCatching {
        canal()
        val n = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_cortafuegos)
            .setContentTitle(getString(R.string.cf_aviso_apagado))
            .setContentText(getString(R.string.cf_aviso_apagado_nota))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.cf_aviso_apagado_nota)))
            .setContentIntent(abrir())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(AVISO_APAGADO, n)
    }
}
