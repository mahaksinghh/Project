# AppGuardian

AppGuardian is an Android APK permission inspector with a web dashboard for reports you choose to share. It checks declared permissions and explains the potential access behind each score.

## Android

Open `android/` in Android Studio with JDK 17, Gradle 8.11.1 and Android SDK 35. From `android/`, run `gradle testDebugUnitTest lintDebug assembleDebug`. GitHub Actions uploads the debug APK when the Android job succeeds.

Open **Choose APK** to inspect a standalone APK up to 100 MiB. Android reads its declared permissions locally and AppGuardian calculates a SHA-256 fingerprint. The temporary APK copy is removed after inspection. The report stays in memory until cleared or the activity is recreated. **Open Android app settings** opens the system screen where users control installed app permissions.

## API and dashboard

1. Run `docker compose up --build` at the repository root. The API listens on port 8000 and exposes `/health` and `/docs`.
2. Run `python3 -m http.server 3000 --directory web` and open [the local dashboard](http://localhost:3000). The dashboard opens with a fictional sample.
3. Open **Connect a private workspace** and generate a workspace key. Save it privately and use it in both clients. Keys grant read, write and delete access to that workspace.
4. Host the API behind HTTPS before connecting Android. Set `POSTGRES_PASSWORD`, `DATABASE_URL`, and `WEB_ORIGINS` for the hosted environment. Set `WEB_ORIGINS` to the exact dashboard origin. For production, use managed PostgreSQL or a backed-up database, and enforce request limits at the HTTPS proxy.
5. After inspection, tap **Share to dashboard**, enter the HTTPS API origin and key, then confirm. Only the package name, fingerprint and declared permissions are sent. The API saves the resulting findings until deleted. The dashboard lets workspace members delete reports.

To run API tests from `backend/`, install `requirements.txt` and `httpx==0.28.1`, export `DATABASE_URL` for a test database, and run `python -m unittest discover -s tests -v`.

The dashboard is deployable as a static Vercel project with root directory `web`. It requires no environment variables. Hosting the dashboard and hosting the FastAPI/PostgreSQL service are independent.

## Detection coverage

Scores are based on a small, deterministic permission rule set. They describe potential access and cannot establish safety or malicious behavior. Android controls permission grants. This release covers standalone APK metadata, with no runtime malware blocking, VPN monitoring, package reputation service or external AI provider.
