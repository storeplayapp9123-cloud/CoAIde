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

      const groqResponse = await fetch("https://api.groq.com/openai/v1/chat/completions", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${env.GROQ_API_KEY}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          model: "llama-3.3-70b-versatile",
          messages,
          temperature: 0.2
        })
      });

      const groqData = await groqResponse.json();

      if (!groqResponse.ok) {
        return new Response(
          JSON.stringify({ error: groqData?.error?.message || "Groq API error" }),
          {
            status: groqResponse.status,
            headers: { "Content-Type": "application/json" }
          }
        );
      }

      const reply = groqData?.choices?.[0]?.message?.content?.trim() || "";
      return new Response(JSON.stringify({ reply }), {
        status: 200,
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
