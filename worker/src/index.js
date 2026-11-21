export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response(JSON.stringify({ error: "Method not allowed" }), {
        status: 405,
        headers: { "Content-Type": "application/json" }
      });
    }

    const appSecret = request.headers.get("X-CoAIde-Token");
    if (!env.APP_CLIENT_TOKEN || appSecret !== env.APP_CLIENT_TOKEN) {
      return new Response(JSON.stringify({ error: "Unauthorized app request" }), {
        status: 401,
        headers: { "Content-Type": "application/json" }
      });
    }

    if (!env.GROQ_API_KEY) {
      return new Response(JSON.stringify({ error: "Server missing GROQ_API_KEY" }), {
        status: 500,
        headers: { "Content-Type": "application/json" }
      });
    }

    try {
      const body = await request.json();
      const history = Array.isArray(body.history) ? body.history : [];
      const userPrompt = typeof body.prompt === "string" ? body.prompt.trim() : "";

      if (!userPrompt) {
        return new Response(JSON.stringify({ error: "Prompt khali nahi hona chahiye" }), {
          status: 400,
          headers: { "Content-Type": "application/json" }
        });
      }

      const systemPrompt = `
Tum 'CoAIde' ho — ek strict aur expert Software & Android Code Assistant.
Niyam:
1. Sirf programming, code likhne, bug fix karne, code review, system design, aur developer tools se jude sawaalon ka jawab do.
2. Agar user coding/programming ke alawa koi sawaal pooche (jaise movies, jokes, general GK, personal advice), toh seedha aur polite tareeke se mana karo aur kaho ki tum 'CoAIde' ho aur sirf coding mein madad kar sakte ho.
3. Code hamesha clean, well-formatted, aur practical explanation ke saath do.
      `.trim();

      const messages = [
        { role: "system", content: systemPrompt },
        ...history.map((m) => ({
          role: m.role === "model" ? "assistant" : m.role,
          content: String(m.content ?? "")
        })),
        { role: "user", content: userPrompt }
      ];

      // Candidate models list (pehle preferred model, phir fallback models)
      const candidateModels = [
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "qwen/qwen3-32b",
        "deepseek-r1-distill-llama-70b",
        "gemma2-9b-it"
      ];

      // Account par available models fetch karke priority mein lagana
      try {
        const modelsRes = await fetch("https://api.groq.com/openai/v1/models", {
          headers: {
            "Authorization": `Bearer ${env.GROQ_API_KEY}`
          }
        });
        if (modelsRes.ok) {
          const modelsJson = await modelsRes.json();
          const availableIds = Array.isArray(modelsJson?.data)
            ? modelsJson.data.map((m) => m.id).filter(Boolean)
            : [];

          // Jo models candidateModels mein hain aur account mein active bhi hain, unhe aage rakhein
          const matched = candidateModels.filter((m) => availableIds.includes(m));
          const extraChatModels = availableIds.filter(
            (id) =>
              !candidateModels.includes(id) &&
              !id.toLowerCase().includes("whisper") &&
              !id.toLowerCase().includes("guard") &&
              !id.toLowerCase().includes("tts")
          );
          const ordered = [...matched, ...extraChatModels];
          if (ordered.length > 0) {
            candidateModels.splice(0, candidateModels.length, ...ordered);
          }
        }
      } catch (_modelsErr) {
        // Agar /models call fail ho toh default candidateModels list hi use hogi
      }

      let lastErrorMsg = "Groq API error";
      let lastStatus = 500;

      for (const modelName of candidateModels) {
        const groqResponse = await fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${env.GROQ_API_KEY}`,
            "Content-Type": "application/json"
          },
          body: JSON.stringify({
            model: modelName,
            messages,
            temperature: 0.2
          })
        });

        const groqData = await groqResponse.json();

        if (groqResponse.ok) {
          const reply = groqData?.choices?.[0]?.message?.content?.trim() || "";
          if (!reply) {
            return new Response(JSON.stringify({ error: "Empty response from model" }), {
              status: 502,
              headers: { "Content-Type": "application/json" }
            });
          }
          return new Response(JSON.stringify({ reply, model: modelName }), {
            status: 200,
            headers: { "Content-Type": "application/json" }
          });
        }

        lastStatus = groqResponse.status;
        lastErrorMsg = groqData?.error?.message || `Model ${modelName} failed`;

        // Agar model exist nahi karta (404) ya access/decommissioned issue hai, toh agla model try karein
        const isModelMissingOrUnavailable =
          groqResponse.status === 404 ||
          groqResponse.status === 400 ||
          /model|decommission|not found|access/i.test(lastErrorMsg);

        if (!isModelMissingOrUnavailable) {
          break;
        }
      }

      return new Response(JSON.stringify({ error: lastErrorMsg }), {
        status: lastStatus,
        headers: { "Content-Type": "application/json" }
      });

    } catch (err) {
      return new Response(JSON.stringify({ error: err.message || "Worker error" }), {
        status: 500,
        headers: { "Content-Type": "application/json" }
      });
    }
  }
};
