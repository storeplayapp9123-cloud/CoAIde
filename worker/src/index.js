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
      const rawHistory = Array.isArray(body.history) ? body.history : [];
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
2. Jab user koi image/screenshot bheje, toh us image ke andar jo UI, text, code, ya error dikh raha hai usko dhyan se dekh kar seedha uska jawab do — kabhi mat kaho ki image nahi dikh rahi jab image data diya gaya ho.
3. Agar user coding/programming ke alawa koi sawaal pooche, toh polite tareeke se kaho ki tum CoAIde ho aur coding/development mein madad ke liye bane ho.
4. Code hamesha clean, well-formatted, aur practical explanation ke saath do.
      `.trim();

      const recentHistory = rawHistory
        .slice(-6)
        .map((m) => {
          const text = String(m.content ?? "");
          const trimmed = text.length > 1200 ? text.slice(0, 1200) + "\n...[trimmed]" : text;
          return {
            role: m.role === "model" ? "assistant" : m.role,
            content: trimmed
          };
        })
        .filter((m) => m.content.trim().length > 0);

      const effectivePrompt = userPrompt || "Is image mein jo UI, code, ya error dikh raha hai uska analysis aur solution batayein.";

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

      const preferredVisionModels = [
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "meta-llama/llama-4-maverick-17b-128e-instruct",
        "llama-3.2-11b-vision-preview",
        "llama-3.2-90b-vision-preview"
      ];

      const preferredTextModels = [
        "llama-3.1-8b-instant",
        "llama-3.3-70b-versatile",
        "qwen/qwen3-32b",
        "openai/gpt-oss-120b",
        "openai/gpt-oss-20b",
        "gemma2-9b-it"
      ];

      const blockedKeywords = [
        "whisper",
        "guard",
        "tts",
        "orpheus",
        "playai",
        "distil-whisper",
        "safeguard"
      ];

      let visionQueue = [...preferredVisionModels];
      let textQueue = [...preferredTextModels];
      let accountModelObjects = [];

      try {
        const modelsRes = await fetch("https://api.groq.com/openai/v1/models", {
          headers: {
            "Authorization": `Bearer ${env.GROQ_API_KEY}`
          }
        });
        if (modelsRes.ok) {
          const modelsJson = await modelsRes.json();
          const dataArr = Array.isArray(modelsJson?.data) ? modelsJson.data : [];
          accountModelObjects = dataArr;
          const availableIds = dataArr.map((m) => m.id).filter(Boolean);

          const detectedVisionFromApi = dataArr
            .filter((m) => {
              const id = String(m?.id || "").toLowerCase();
              if (!id || blockedKeywords.some((kw) => id.includes(kw))) return false;
              const caps = JSON.stringify(m).toLowerCase();
              return /vision|scout|maverick|vl|multimodal|image/.test(id) || /vision|image/.test(caps);
            })
            .map((m) => m.id);

          const matchedVision = preferredVisionModels.filter((m) => availableIds.includes(m));
          const mergedVision = [...new Set([...matchedVision, ...detectedVisionFromApi])];
          if (mergedVision.length > 0) {
            visionQueue = mergedVision;
          }

          const matchedText = preferredTextModels.filter((m) => availableIds.includes(m));
          const extraText = availableIds.filter((id) => {
            const lower = id.toLowerCase();
            return !preferredTextModels.includes(id) &&
              !mergedVision.includes(id) &&
              !blockedKeywords.some((kw) => lower.includes(kw));
          });
          const mergedText = [...new Set([...matchedText, ...extraText])];
          if (mergedText.length > 0) {
            textQueue = mergedText;
          }
        }
      } catch (_modelsErr) {
        // Fallback to default queues
      }

      // Agar image bheji gayi hai, toh pehle vision models try karein; agar vision fail ho toh non-vision models se aage badhein
      const attemptPlan = imageBase64
        ? [
            ...visionQueue.map((modelName) => ({ modelName, sendImage: true })),
            ...textQueue.map((modelName) => ({ modelName, sendImage: false }))
          ]
        : textQueue.map((modelName) => ({ modelName, sendImage: false }));

      let lastErrorMsg = "Groq API error";
      let lastStatus = 500;
      let visionFailedWithImage = false;

      for (const step of attemptPlan) {
        const messages = [
          { role: "system", content: systemPrompt },
          ...recentHistory,
          {
            role: "user",
            content: step.sendImage ? multimodalUserContent : textFallbackUserContent
          }
        ];

        const groqResponse = await fetch("https://api.groq.com/openai/v1/chat/completions", {
          method: "POST",
          headers: {
            "Authorization": `Bearer ${env.GROQ_API_KEY}`,
            "Content-Type": "application/json"
          },
          body: JSON.stringify({
            model: step.modelName,
            messages,
            temperature: 0.2,
            max_tokens: 2048
          })
        });

        const groqData = await groqResponse.json();

        if (groqResponse.ok) {
          let reply = groqData?.choices?.[0]?.message?.content?.trim() || "";
          if (!reply) {
            return new Response(JSON.stringify({ error: "Empty response from model" }), {
              status: 502,
              headers: { "Content-Type": "application/json" }
            });
          }

          if (imageBase64 && !step.sendImage && visionFailedWithImage) {
            const availableList = accountModelObjects.map((m) => m.id).filter(Boolean).join(", ");
            reply = `Note: Is Groq account ke active models (${availableList || "check console.groq.com"}) mein image-vision model ne response nahi diya, isliye image pixel-level par nahi dekhi ja saki.\n\n${reply}`;
          }

          return new Response(JSON.stringify({ reply, model: step.modelName }), {
            status: 200,
            headers: { "Content-Type": "application/json" }
          });
        }

        lastStatus = groqResponse.status;
        lastErrorMsg = groqData?.error?.message || `Model ${step.modelName} failed`;

        if (step.sendImage) {
          visionFailedWithImage = true;
        }

        const shouldTryNextModel =
          groqResponse.status === 400 ||
          groqResponse.status === 404 ||
          groqResponse.status === 413 ||
          groqResponse.status === 429 ||
          /model|decommission|not found|access|terms|too large|tokens per minute|vision|image_url|content/i.test(lastErrorMsg);

        if (!shouldTryNextModel) {
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
