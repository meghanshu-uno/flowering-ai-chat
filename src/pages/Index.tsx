import { useState, useRef, useEffect, useCallback } from "react";
import { Send, Square, Plus, MessageSquare, Copy, RefreshCw, Check, MoreVertical, Download, Brain } from "lucide-react";
import { MODELS, Model, streamChat, generateTitle, ChatMessage } from "@/lib/openrouter";
import { useChatStore } from "@/hooks/useChatStore";
import ModelSelector from "@/components/chat/ModelSelector";
import MessageBubble from "@/components/chat/MessageBubble";
import ArtifactPanel from "@/components/chat/ArtifactPanel";
import ChatsDrawer from "@/components/chat/ChatsDrawer";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import logo from "@/assets/logo.png";

function getGreeting(): string {
  const now = new Date();
  const istOffset = 5.5 * 60;
  const utcMinutes = now.getUTCHours() * 60 + now.getUTCMinutes();
  const istMinutes = (utcMinutes + istOffset) % (24 * 60);
  const hour = Math.floor(istMinutes / 60);
  if (hour >= 5 && hour < 12) return "Good morning";
  if (hour >= 12 && hour < 17) return "Good afternoon";
  if (hour >= 17 && hour < 21) return "Good evening";
  return "Good night";
}

export default function Index() {
  const store = useChatStore();
  const [model, setModel] = useState<Model>(MODELS[0]);
  const [input, setInput] = useState("");
  const [isStreaming, setIsStreaming] = useState(false);
  const [isThinking, setIsThinking] = useState(false);
  const [artifact, setArtifact] = useState<{ code: string; language: string } | null>(null);

  useEffect(() => {
    setArtifact(null);
  }, [store.activeChatId]);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const scrollToBottom = () => messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  useEffect(scrollToBottom, [store.activeChat?.messages]);

  useEffect(() => {
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
      textareaRef.current.style.height = Math.min(textareaRef.current.scrollHeight, 160) + "px";
    }
  }, [input]);

  const handleSend = useCallback(async () => {
    const text = input.trim();
    if (!text || isStreaming) return;

    let chatId = store.activeChatId;
    if (!chatId) {
      chatId = store.createChat(model.id);
    }

    const chat = store.chats.find((c) => c.id === chatId) || { messages: [] };
    const userMsg: ChatMessage = { role: "user", content: text };
    const newMessages = [...chat.messages, userMsg];
    store.updateMessages(chatId, newMessages);
    setInput("");
    setIsStreaming(true);
    setIsThinking(true);

    if (chat.messages.length === 0) {
      generateTitle(text, model).then((title) => store.updateTitle(chatId!, title));
    }

    const controller = new AbortController();
    abortRef.current = controller;

    let assistantContent = "";
    store.updateMessages(chatId, [...newMessages, { role: "assistant", content: "" }]);

    try {
      await streamChat({
        messages: newMessages,
        model,
        signal: controller.signal,
        onThinkingDone: () => setIsThinking(false),
        onDelta: (delta) => {
          assistantContent += delta;
          store.updateMessages(chatId!, [...newMessages, { role: "assistant", content: assistantContent }]);
        },
        onDone: () => {},
      });
    } catch (err: any) {
      if (err.name !== "AbortError") {
        assistantContent += "\n\n*Error: " + err.message + "*";
        store.updateMessages(chatId!, [...newMessages, { role: "assistant", content: assistantContent }]);
      }
    } finally {
      setIsStreaming(false);
      setIsThinking(false);
      abortRef.current = null;
    }
  }, [input, isStreaming, model, store]);

  const handleStop = () => abortRef.current?.abort();

  const messages = store.activeChat?.messages || [];
  const hasMessages = messages.length > 0;

  const [copiedChat, setCopiedChat] = useState(false);

  const handleCopyChat = () => {
    const text = messages.map((m) => `${m.role === "user" ? "You" : "Bhosdu AI"}: ${m.content}`).join("\n\n");
    navigator.clipboard.writeText(text);
    setCopiedChat(true);
    setTimeout(() => setCopiedChat(false), 2000);
  };

  const handleRegenerate = useCallback(async () => {
    if (isStreaming || !store.activeChatId || messages.length < 2) return;
    const chatId = store.activeChatId;
    const withoutLast = messages.slice(0, -1);
    store.updateMessages(chatId, withoutLast);
    setIsStreaming(true);
    setIsThinking(true);

    const controller = new AbortController();
    abortRef.current = controller;
    let assistantContent = "";
    store.updateMessages(chatId, [...withoutLast, { role: "assistant" as const, content: "" }]);

    try {
      await streamChat({
        messages: withoutLast,
        model,
        signal: controller.signal,
        onThinkingDone: () => setIsThinking(false),
        onDelta: (delta) => {
          assistantContent += delta;
          store.updateMessages(chatId, [...withoutLast, { role: "assistant" as const, content: assistantContent }]);
        },
        onDone: () => {},
      });
    } catch (err: any) {
      if (err.name !== "AbortError") {
        assistantContent += "\n\n*Error: " + err.message + "*";
        store.updateMessages(chatId, [...withoutLast, { role: "assistant" as const, content: assistantContent }]);
      }
    } finally {
      setIsStreaming(false);
      setIsThinking(false);
      abortRef.current = null;
    }
  }, [isStreaming, model, store, messages]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleDownloadApk = () => {
    const link = document.createElement("a");
    link.href = "/BhosduAi.apk";
    link.download = "BhosduAi.apk";
    link.click();
  };

  return (
    <div className="flex h-screen overflow-hidden" style={{ backgroundColor: "hsl(var(--chat-bg))" }}>
      <ChatsDrawer
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        chats={store.chats}
        activeChatId={store.activeChatId}
        onSelectChat={store.setActiveChatId}
        onDeleteChat={store.deleteChat}
      />

      <div className="flex-1 flex flex-col min-w-0 relative">
        {/* Top bar */}
        <div className="flex items-center justify-between px-4 py-3">
          <div className="flex items-center gap-2">
            <button
              onClick={() => store.createChat(model.id)}
              className="w-9 h-9 rounded-xl bg-primary flex items-center justify-center text-primary-foreground hover:bg-primary/90 transition-colors shadow-lg shadow-primary/20"
              title="New Chat"
            >
              <Plus size={18} />
            </button>
            <button
              onClick={() => setDrawerOpen(true)}
              className="w-9 h-9 rounded-xl bg-muted flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors"
              title="Your Chats"
            >
              <MessageSquare size={17} />
            </button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  className="w-9 h-9 rounded-xl bg-muted flex items-center justify-center text-muted-foreground hover:text-foreground hover:bg-secondary transition-colors"
                  title="More options"
                >
                  <MoreVertical size={17} />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="rounded-xl">
                <DropdownMenuItem onClick={handleDownloadApk} className="gap-2 cursor-pointer">
                  <Download size={15} />
                  Download for Android
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
          <div className="flex items-center gap-2">
            <img src={logo} alt="Bhosdu AI" width={24} height={24} className="opacity-70" />
            <span className="text-sm font-semibold text-foreground tracking-tight">Bhosdu AI</span>
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto">
          {!hasMessages ? (
            <div className="flex flex-col items-center justify-center h-full px-4">
              <div className="flex items-center gap-3 mb-8">
                <img src={logo} alt="Bhosdu AI" width={40} height={40} />
                <h1 className="text-3xl font-semibold tracking-tight" style={{ color: "hsl(var(--greeting))" }}>
                  {getGreeting()}
                </h1>
              </div>

              <div className="w-full max-w-2xl">
                <div className="bg-card rounded-2xl border border-border shadow-xl overflow-hidden">
                  <textarea
                    ref={textareaRef}
                    value={input}
                    onChange={(e) => setInput(e.target.value)}
                    onKeyDown={handleKeyDown}
                    placeholder="How can I help you today?"
                    rows={1}
                    className="w-full bg-transparent px-5 pt-4 pb-2 text-foreground text-sm resize-none outline-none placeholder:text-muted-foreground"
                    style={{ minHeight: "44px", maxHeight: "160px" }}
                  />
                  <div className="flex items-center justify-end px-3 pb-3">
                    <div className="flex items-center gap-2">
                      <ModelSelector selected={model} onSelect={setModel} />
                      <button
                        onClick={handleSend}
                        disabled={!input.trim()}
                        className="p-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                      >
                        <Send size={16} />
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          ) : (
            <div className="max-w-3xl mx-auto px-4 py-6">
              {messages.map((msg, i) => (
                <MessageBubble
                  key={i}
                  message={msg}
                  isLast={i === messages.length - 1 && msg.role === "assistant"}
                  onArtifact={(code, lang) => setArtifact({ code, language: lang })}
                />
              ))}
              {/* Thinking indicator */}
              {isThinking && (
                <div className="flex items-center gap-2 px-2 py-4">
                  <Brain size={16} className="text-primary animate-pulse" />
                  <span className="text-sm text-muted-foreground animate-pulse">Thinking...</span>
                </div>
              )}
              {/* Typing dots when streaming but no content yet and not thinking */}
              {isStreaming && !isThinking && messages[messages.length - 1]?.content === "" && (
                <div className="flex gap-1.5 px-2 py-4">
                  <div className="typing-dot w-2 h-2 rounded-full bg-primary" />
                  <div className="typing-dot w-2 h-2 rounded-full bg-primary" />
                  <div className="typing-dot w-2 h-2 rounded-full bg-primary" />
                </div>
              )}
              {!isStreaming && messages.length >= 2 && messages[messages.length - 1]?.role === "assistant" && (
                <div className="flex items-center gap-2 mt-1 mb-4">
                  <button
                    onClick={handleCopyChat}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
                  >
                    {copiedChat ? <Check size={13} className="text-emerald-400" /> : <Copy size={13} />}
                    {copiedChat ? "Copied" : "Copy chat"}
                  </button>
                  <button
                    onClick={handleRegenerate}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
                  >
                    <RefreshCw size={13} />
                    Regenerate
                  </button>
                </div>
              )}
              <div ref={messagesEndRef} />
            </div>
          )}
        </div>

        {/* Bottom input */}
        {hasMessages && (
          <div className="px-4 py-3" style={{ backgroundColor: "hsl(var(--chat-bg))" }}>
            <div className="max-w-3xl mx-auto">
              <div className="bg-card rounded-2xl border border-border overflow-hidden">
                <textarea
                  ref={textareaRef}
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={handleKeyDown}
                  placeholder="Reply to Bhosdu AI..."
                  rows={1}
                  className="w-full bg-transparent px-5 pt-4 pb-2 text-foreground text-sm resize-none outline-none placeholder:text-muted-foreground"
                  style={{ minHeight: "44px", maxHeight: "160px" }}
                />
                <div className="flex items-center justify-end px-3 pb-3">
                  <div className="flex items-center gap-2">
                    <ModelSelector selected={model} onSelect={setModel} />
                    {isStreaming ? (
                      <button
                        onClick={handleStop}
                        className="p-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
                      >
                        <Square size={16} />
                      </button>
                    ) : (
                      <button
                        onClick={handleSend}
                        disabled={!input.trim()}
                        className="p-2 rounded-lg bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                      >
                        <Send size={16} />
                      </button>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Artifact Panel */}
      {artifact && (
        <ArtifactPanel
          code={artifact.code}
          language={artifact.language}
          onClose={() => setArtifact(null)}
        />
      )}
    </div>
  );
}
