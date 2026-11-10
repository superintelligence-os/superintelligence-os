// Las IA que puede usar el sistema. Tres maneras de entrar:
//   suscripcion: con la cuenta de pago de siempre, a través de su programa oficial, que habla MCP
//                (Claude Code, Codex de OpenAI, Gemini CLI de Google).
//   clave:       pago por uso con la clave de la API de cada uno (bucle de agente propio).
//   local:       un modelo que corre en el propio móvil (llama.cpp), sin internet ni cuenta.
// El acento y el logo son los de cada marca: con lo que se entre, el sistema se pinta así.

export const PROVEEDORES = {
  claude: {
    nombre: "Claude", empresa: "Anthropic", tipo: "suscripcion", motor: "claude",
    acento: ["#D97757", "#C6613F"], logo: "claude",
    descripcion: "Con tu plan Pro o Max de Claude.",
  },
  chatgpt: {
    nombre: "ChatGPT", empresa: "OpenAI", tipo: "suscripcion", motor: "codex",
    acento: ["#10A37F", "#0D8A6B"], logo: "chatgpt",
    descripcion: "Con tu plan Plus, Pro o Business de ChatGPT.",
    modelos: { rapido: "gpt-5-mini", normal: "gpt-5" },
  },
  gemini: {
    nombre: "Gemini", empresa: "Google", tipo: "suscripcion", motor: "gemini",
    acento: ["#4C8DF6", "#2F6FDB"], logo: "gemini",
    descripcion: "Con tu cuenta de Google (gratis o con Google AI Pro).",
    modelos: { rapido: "gemini-2.5-flash", normal: "gemini-2.5-pro" },
  },
  kimi_code: {
    nombre: "Kimi", empresa: "Moonshot AI", tipo: "suscripcion", motor: "kimi",
    acento: ["#3B82F6", "#2563EB"], logo: "kimi", vision: false,
    descripcion: "Con tu plan Plus, Pro, Max o Ultra de Kimi (Kimi Code).",
  },
  // ---- Pago por uso con clave ----
  deepseek: {
    nombre: "DeepSeek", empresa: "DeepSeek", tipo: "clave", motor: "bucle", base: "https://api.deepseek.com/v1",
    acento: ["#4D6BFE", "#3A55E0"], logo: "deepseek", vision: false, claveDe: "https://platform.deepseek.com/api_keys",
    descripcion: "Muy barato. Pago por uso con tu clave.",
    preferidos: { rapido: [/^deepseek-chat$/], normal: [/^deepseek-chat$/] },
  },
  kimi: {
    nombre: "Kimi", empresa: "Moonshot AI", tipo: "clave", motor: "bucle", base: "https://api.moonshot.ai/v1",
    acento: ["#3B82F6", "#2563EB"], logo: "kimi", vision: false, claveDe: "https://platform.moonshot.ai/console/api-keys",
    descripcion: "Kimi K2, bueno con herramientas. Pago por uso.",
    preferidos: { rapido: [/kimi-k2.*turbo/i, /kimi-k2/i, /moonshot-v1-8k/], normal: [/kimi-k2(?!.*turbo)/i, /kimi-k2/i, /moonshot/] },
  },
  gemini_api: {
    nombre: "Gemini", empresa: "Google", tipo: "clave", motor: "bucle", base: "https://generativelanguage.googleapis.com/v1beta/openai",
    acento: ["#4C8DF6", "#2F6FDB"], logo: "gemini", claveDe: "https://aistudio.google.com/apikey",
    descripcion: "Gemini con clave de Google AI Studio.",
    preferidos: { rapido: [/gemini-[\d.]+-flash(?!.*lite)/, /flash/], normal: [/gemini-[\d.]+-pro/, /pro/] },
  },
  openai: {
    nombre: "ChatGPT", empresa: "OpenAI", tipo: "clave", motor: "bucle", base: "https://api.openai.com/v1",
    acento: ["#10A37F", "#0D8A6B"], logo: "chatgpt", claveDe: "https://platform.openai.com/api-keys",
    descripcion: "Modelos de OpenAI con clave de la API.",
    preferidos: { rapido: [/^gpt-[\d.]+-mini$/, /mini/], normal: [/^gpt-[\d.]+$/, /^gpt-/] },
  },
  anthropic: {
    nombre: "Claude", empresa: "Anthropic", tipo: "clave", motor: "bucle", base: "https://api.anthropic.com/v1",
    acento: ["#D97757", "#C6613F"], logo: "claude", claveDe: "https://console.anthropic.com/settings/keys",
    descripcion: "Claude con clave de la API (pago por uso).",
    preferidos: { rapido: [/haiku/], normal: [/sonnet/] },
  },
  mistral: {
    // El plan Pro de Le Chat incluye crédito de API cada mes: se entra con la clave de la cuenta.
    nombre: "Mistral", empresa: "Mistral AI", tipo: "clave", grupo: "suscripcion", motor: "bucle", base: "https://api.mistral.ai/v1",
    acento: ["#FA520F", "#D9440A"], logo: "mistral", claveDe: "https://console.mistral.ai/api-keys",
    descripcion: "Con tu plan Pro de Le Chat (incluye la API): pega su clave.",
    preferidos: { rapido: [/mistral-small-latest/, /small/], normal: [/mistral-large-latest/, /mistral-medium-latest/, /large/] },
  },
  grok: {
    // Los planes Plus, Premium y Super de Grok incluyen la API: se entra con la clave del plan.
    nombre: "Grok", empresa: "xAI", tipo: "clave", grupo: "suscripcion", motor: "bucle", base: "https://api.x.ai/v1",
    acento: ["#A3A3A3", "#525252"], logo: "grok", claveDe: "https://console.x.ai",
    descripcion: "Con tu plan Plus, Premium o Super (incluye la API): pega su clave.",
    preferidos: { rapido: [/grok.*mini/, /grok.*fast/], normal: [/^grok-\d(?!.*mini)/, /grok/] },
  },
  openrouter: {
    nombre: "OpenRouter", empresa: "OpenRouter", tipo: "clave", motor: "bucle", base: "https://openrouter.ai/api/v1",
    acento: ["#6467F2", "#4F46E5"], logo: "openrouter", claveDe: "https://openrouter.ai/keys",
    descripcion: "Cientos de modelos con una sola clave.",
    preferidos: { rapido: [/deepseek\/deepseek-chat/, /qwen.*instruct/], normal: [/anthropic\/claude-sonnet/, /deepseek\/deepseek-chat/] },
  },
  // ---- En el móvil ----
  local: {
    nombre: "Local", empresa: "en tu móvil", tipo: "local", motor: "bucle", base: "http://127.0.0.1:8181/v1",
    acento: ["#7B6CF6", "#5B4BE0"], logo: "local",
    descripcion: "Sin internet ni cuenta: todo se queda en el móvil.",
  },
};

/**
 * Modelos locales según la RAM del móvil (GGUF cuantizados de Hugging Face; llama.cpp).
 * ram: GB mínimos de RAM total del móvil para que vaya con soltura junto al resto del sistema.
 */
export const MODELOS_LOCALES = [
  { id: "qwen3-8b", nombre: "Qwen3 8B", ram: 12, tamano: 5.0, url: "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf", nota: "El más capaz que cabe en un móvil de gama alta." },
  { id: "qwen3-4b", nombre: "Qwen3 4B", ram: 8, tamano: 2.5, url: "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf", nota: "Buen equilibrio: maneja bien las herramientas." },
  { id: "qwen3-1.7b", nombre: "Qwen3 1.7B", ram: 6, tamano: 1.1, url: "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf", nota: "Ligero, para tareas sencillas." },
  { id: "qwen3-0.6b", nombre: "Qwen3 0.6B", ram: 3, tamano: 0.6, url: "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf", nota: "Mínimo: preguntas cortas, poco fiable con herramientas." },
];

export function modeloLocalPara(ramGb) {
  return MODELOS_LOCALES.find((m) => ramGb >= m.ram) || null;
}

/** Lo que ve la app (y el escritorio): sin claves. */
export function publico(id) {
  const p = PROVEEDORES[id];
  if (!p) return null;
  const { preferidos, ...resto } = p;
  return { id, ...resto };
}
