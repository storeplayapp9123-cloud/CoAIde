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
      const imageBase64 = typeof body.imageBase64 === "string" ? body.imageBase64.trim() : "";
      const imageMime = typeof body.imageMime === "string" && body.imageMime.trim()
        ? body.imageMime.trim()
        : "image/jpeg";

      if (!userPrompt && !imageBase64) {
        return new Response(JSON.stringify({ error: "Prompt ya image me se kuch bhejein" }), {
          status: 400,
          headers: { "Content-Type": "application/json" }
        });
      }

      const systemPrompt = `
Tum 'CoAIde' ho — ek expert Software, Android, aur Code Assistant.
Tumhe Aamir Alam ne banaya hai. Agar koi pooche ki tumhe kisne banaya ya tumhara creator/developer kaun hai, toh garv aur spasht roop se batao ki tumhe Aamir Alam ne banaya hai.

Niyam:
1. Coding, code likhne, bug fix karne, code review, system design, Android/Kotlin/Java/Compose/GitHub Actions, UI/error screenshots, aur developer tools se jude sawaalon ka jawab do.
2. Agar user coding/programming ke alawa koi sawaal pooche, toh polite tareeke se kaho ki tum CoAIde ho aur coding/development mein madad ke liye bane ho.
3. Code hamesha clean, well-formatted, aur practical explanation ke saath do.
      `.trim();

      const effectivePrompt = userPrompt || "Is image mein jo code, UI, ya error dikh raha hai uska analysis aur solution batayein.";

      const multimodalUserContent = imageBase64
        ? [
            { type: "text", text: effectivePrompt },
            {
              type: "image_url",
              image_url: {
                url: `data:${imageMime};base64,${imageBase64}`
              }
            }
          ]
        : effectivePrompt;

      const textFallbackUserContent = imageBase64
        ? `${effectivePrompt}\n\n[User ne ek image attach ki thi (${imageMime})]`
        : effectivePrompt;

      const baseHistory = history.map((m) => ({
        role: m.role === "model" ? "assistant" : m.role,
        content: String(m.content ?? "")
      }));

      const visionModels = [
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "meta-llama/llama-4-maverick-17b-128e-instruct",
        "llama-3.2-11b-vision-preview",
        "llama-3.2-90b-vision-preview"
      ];

      const textModels = [
        "llama-3.3-70b-versatile",
        "llama-3.1-8b-instant",
        "qwen/qwen3-32b",
        "deepseek-r1-distill-llama-70b",
        "gemma2-9b-it"
      ];

      let candidateModels = imageBase64
        ? [...visionModels, ...textModels]
        : [...textModels];

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

          const preferred = candidateModels.filter((m) => availableIds.includes(m));
          const extraModels = availableIds.filter(
            (id) =>
              !candidateModels.includes(id) &&
              !id.toLowerCase().includes("whisper") &&
              !id.toLowerCase().includes("guard") &&
              !id.toLowerCase().includes("tts")
          );
          const ordered = [...preferred, ...extraModels];
          if (ordered.length > 0) {
            candidateModels = ordered;
          }
        }
      } catch (_modelsErr) {
        // Fallback to default candidateModels
      }

      let lastErrorMsg = "Groq API error";
      let lastStatus = 500;

      for (const modelName of candidateModels) {
        const isVisionModel = /vision|scout|maverick|vl/i.test(modelName);
        const finalUserContent = (imageBase64 && isVisionModel)
          ? multimodalUserContent
          : textFallbackUserContent;

        const messages = [
          { role: "system", content: systemPrompt },
          ...baseHistory,
          { role: "user", content: finalUserContent }
        ];

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

        const isModelMissingOrUnavailable =
          groqResponse.status === 404 ||
          groqResponse.status === 400 ||
          /model|decommission|not found|access|image_url|vision/i.test(lastErrorMsg);

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
