export interface Model {
  id: string;
  name: string;
  modelId: string;
  apiUrl: string;
  apiKey: string;
}

const GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
const GROQ_KEY = "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";

export const MODELS: Model[] = [
  { id: "llama-70b", name: "Llama 3.3 70B — Versatile, high quality", modelId: "llama-3.3-70b-versatile", apiUrl: GROQ_URL, apiKey: GROQ_KEY },
  { id: "llama-scout", name: "Llama 4 Scout 17B — Fast, efficient", modelId: "meta-llama/llama-4-scout-17b-16e-instruct", apiUrl: GROQ_URL, apiKey: GROQ_KEY },
  { id: "qwen3-32b", name: "Qwen 3 32B — Balanced, multilingual", modelId: "qwen/qwen3-32b", apiUrl: GROQ_URL, apiKey: GROQ_KEY },
  { id: "kimi-k2", name: "Kimi K2 — Strong reasoning", modelId: "moonshotai/kimi-k2-instruct", apiUrl: GROQ_URL, apiKey: GROQ_KEY },
  { id: "gpt-oss-120b", name: "GPT-OSS 120B — Most capable", modelId: "openai/gpt-oss-120b", apiUrl: GROQ_URL, apiKey: GROQ_KEY },
];

export const FLUX_API_KEY = "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";
export const CLOUDFLARE_TOKEN = "cfut_GkPH8by21ybb0kvd0sclLZwgop0f69irrJN7GRsN45d4c620";

export interface ChatMessage {
  role: "user" | "assistant";
  content: string;
}

const SYSTEM_PROMPT = `You are Axo Ai, a powerful and accurate AI assistant. Your knowledge was last updated in 2026. The current year is 2026. When asked about time, dates, or current events, respond accordingly based on 2026.

Important instructions:
- Always provide thorough, well-researched, and accurate answers
- When you see a URL/link in the user's message, acknowledge it and provide information about what you know regarding that URL or topic
- Structure your responses clearly with headings and bullet points when appropriate
- For code questions, always provide complete, working code examples
- If you're unsure about something, say so rather than guessing
- Be concise but comprehensive`;

export async function streamChat({
  messages,
  model,
  onDelta,
  onDone,
  onThinkingDone,
  signal,
}: {
  messages: ChatMessage[];
  model: Model;
  onDelta: (text: string) => void;
  onDone: () => void;
  onThinkingDone?: () => void;
  signal?: AbortSignal;
}) {
  const resp = await fetch(model.apiUrl, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${model.apiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: model.modelId,
      messages: [
        { role: "system", content: SYSTEM_PROMPT },
        ...messages,
      ],
      stream: true,
    }),
    signal,
  });

  if (!resp.ok || !resp.body) {
    const errorText = await resp.text();
    throw new Error(`API error ${resp.status}: ${errorText}`);
  }

  if (onThinkingDone) onThinkingDone();

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let newlineIdx: number;
    while ((newlineIdx = buffer.indexOf("\n")) !== -1) {
      let line = buffer.slice(0, newlineIdx);
      buffer = buffer.slice(newlineIdx + 1);
      if (line.endsWith("\r")) line = line.slice(0, -1);
      if (!line.startsWith("data: ")) continue;
      const jsonStr = line.slice(6).trim();
      if (jsonStr === "[DONE]") {
        onDone();
        return;
      }
      try {
        const parsed = JSON.parse(jsonStr);
        const content = parsed.choices?.[0]?.delta?.content;
        if (content) onDelta(content);
      } catch {
        // partial JSON, skip
      }
    }
  }
  onDone();
}

export async function generateTitle(firstMessage: string, model: Model): Promise<string> {
  try {
    const resp = await fetch(model.apiUrl, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${model.apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: model.modelId,
        messages: [
          {
            role: "user",
            content: `Generate a very short title (max 5 words) for a conversation that starts with this message. Return ONLY the title, nothing else:\n\n"${firstMessage}"`,
          },
        ],
      }),
    });
    const data = await resp.json();
    const title = data.choices?.[0]?.message?.content?.trim();
    return title || firstMessage.slice(0, 40);
  } catch {
    return firstMessage.slice(0, 40);
  }
}
