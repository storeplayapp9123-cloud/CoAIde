
# CoAIde — AI Coding Studio

CoAIde is a responsive AI coding assistant with an Android wrapper.
The frontend sends requests to your backend; the Groq API key stays
on the backend.

## Deploy the backend

Deploy the `server` folder to a Python web service.

Install command:

    pip install -r requirements.txt

Start command:

    uvicorn main:app --host 0.0.0.0 --port $PORT

Set the hosting service's working directory to `server`, if supported.

Configure these environment variables in the hosting dashboard:

- `GROQ_API_KEY`: your private Groq API key
- `GROQ_MODEL`: `openai/gpt-oss-20b`
- `CORS_ORIGINS`: use `*` only for initial testing; configure actual
  website origins before public production use.

Verify that `/health` returns:

    {"status":"ok"}

Never put your Groq key in the app, GitHub source, or APK.

## Build Android APK

1. Commit all project files to the `main` branch.
2. Open GitHub Actions.
3. Select `Build CoAIde APK`.
4. Open the latest successful run.
5. Download `CoAIde-App-APK` and extract `app-debug.apk`.

This is a debug APK for testing. A Play Store release requires a
signed release build and securely stored signing secrets.

## Configure the app

Open CoAIde and enter your deployed backend base URL in the
Backend URL field. Do not append `/api/generate`.

## Limitations

- This version generates code but does not compile or execute it.
- Desktop use is through the responsive browser interface.
- Native Windows, macOS, and Linux packages need separate build workflows.
- Before public launch, add authentication, rate limits, and usage monitoring.
- Review and test generated code before using it.
