# ReadySetRecruit

A free website that helps kids find robotics teams near them — community teams first, school teams too — and apply in one place.

- **Students** search by ZIP (grade optional; filter by program, team type, distance, "taking applications"), view team pages, and either apply right on ReadySetRecruit or jump to the team's own website / join page. Student accounts can save drafts and track status.
- **Teams** create a profile, then build their own application: pick eligible grades, add as many questions as they want (paragraph, short answer, multiple choice) with optional word limits, set a deadline, open/close it, and review applicants (accept / waitlist / decline, private notes, CSV export).
- **Teams that haven't signed up** are still listed: on first start the server imports every active US FTC team from [FTCScout](https://ftcscout.org) (no key needed) and, with a free key, every FRC team from The Blue Alliance. A scraper visits their website, pulls a description, finds a "join / apply / recruit" link and a contact email, so the listing points students to the right place. Teams can **claim** their listing (an admin approves).

```
frontend/   React + TypeScript + Vite   → static site (Cloudflare Pages / Netlify / Vercel, free)
backend/    Spring Boot 3 + Java 21      → Docker container (Render free tier)
            Postgres                     → Neon free tier (H2 file DB is used automatically for local dev)
.github/    nightly scraper trigger + CI
```

---

## Run it locally

Requirements: Java 21, Maven 3.9+, Node 20+.

Optional: copy `backend/.env.example` to `backend/.env` and put your free The Blue Alliance key in it (`TBA_API_KEY=...`) to also import FRC teams. `.env` is gitignored, so the key never ends up on GitHub; on Render, set it as an environment variable instead.

```bash
# 1. API (http://localhost:8080) — uses a local H2 database in backend/data/, loads demo data on first run
cd backend
mvn spring-boot:run

# 2. Website (http://localhost:5173) — proxies /api to the backend
cd frontend
npm install
npm run dev
```

Demo logins (password `password123`): `student@demo.org`, `team@demo.org`. Search ZIP **94040** to see the demo teams.

On first start the backend also imports the real team lists in the background (about 6,500 active US FTC teams take ~1 minute), then starts checking team websites, 100 at a time every 5 minutes while it's running. Watch the console for `Import: saved ...` and `Job scrape finished ...`. After that, any US ZIP code works.
To get an admin account locally, start the backend with `ADMIN_EMAILS=you@example.com` and register with that email.

Run the backend tests with `mvn test` (includes an end-to-end API test of search → apply → review → claim).

---

## Deploy for free

Everything below has a free tier with no credit card needed (as of October 2026 — double-check current terms):

| Piece | Host | Free tier notes |
|---|---|---|
| Database | [Neon](https://neon.com/pricing) | 1 GB Postgres per project, scales to zero when idle |
| API | [Render](https://render.com) web service | 512 MB RAM, **sleeps after 15 min idle** (first request then takes ~1 min) |
| Website | Cloudflare Pages, Netlify or Vercel | Static hosting, fast and always on |

> Why not keep the database on Render too? Render's free Postgres is deleted after 30 days. Neon's free plan is permanent.

### 1. Database (Neon)
1. Create a project at neon.com. Copy the connection details.
2. Build the JDBC URL: `jdbc:postgresql://<host>/<database>?sslmode=require` — user and password go in separate variables.

### 2. API (Render)
1. Push this repo to GitHub.
2. In Render: **New → Blueprint**, pick the repo (it reads `render.yaml`). Or create a Web Service manually with *Docker*, root directory `backend`.
3. Set environment variables:
   - `DATABASE_URL`, `DB_USER`, `DB_PASSWORD` — from Neon
   - `JWT_SECRET` — any random string of 32+ characters (Blueprint generates one)
   - `FRONTEND_ORIGIN` — your website URL, e.g. `https://readysetrecruit.pages.dev` (comma-separate several)
   - `ADMIN_EMAILS` — your email; register with it to become admin
   - `CRON_TOKEN` — random string (Blueprint generates one)
   - `SEED_DEMO=false`
   - optional: `TBA_API_KEY`, `FTC_API_USERNAME` + `FTC_API_TOKEN`, `BRAVE_API_KEY` (see below)
4. Check `https://<your-api>.onrender.com/api/health` returns `{"status":"ok"}`.

### 3. Website (Cloudflare Pages / Netlify / Vercel)
- Root directory: `frontend` · Build command: `npm run build` · Output: `dist`
- Environment variable: `VITE_API_URL=https://<your-api>.onrender.com`
- Page refreshes on deep links already work (`public/_redirects` for Netlify/Cloudflare, `vercel.json` for Vercel).

### 4. Nightly scraper (GitHub Actions, free)
In your GitHub repo → Settings → Secrets and variables → Actions, add `BACKEND_URL` and `CRON_TOKEN` (same as on Render).
`.github/workflows/scrape.yml` then wakes the API every night and checks a batch of team websites. You can also run it from the Actions tab or the Admin page.

**About the sleep:** on Render's free plan the API sleeps when nobody's using it, so the first visitor after a quiet period waits about a minute. The website shows a friendly "server waking up" message if a request fails. If that becomes annoying, Render's cheapest paid instance removes the sleep; nothing in the code needs to change.

---

## Filling the directory

Imports run **automatically** the first time the server starts (`AUTO_IMPORT=true`), and the GitHub Actions job refreshes them every Sunday. You can also start them from Admin page → **Background jobs**:

| Source | What you need | What you get |
|---|---|---|
| **FTCScout** (FTC) | Nothing | Every US FTC team that played at an event this season or last (~6,500), with city and website |
| **The Blue Alliance** (FRC) | Free API key from thebluealliance.com/account → `TBA_API_KEY` | All US FRC teams for the year, with ZIP, school and website |
| **FIRST FTC Events API** (optional) | Free key from ftc-events.firstinspires.org/services/API → `FTC_API_USERNAME`, `FTC_API_TOKEN` | Used instead of FTCScout when set |
| **CSV** | Nothing | Anything else — VEX, FLL, local clubs, a school district's list |

Teams are placed on the map using bundled [GeoNames](https://www.geonames.org) ZIP and city data (`backend/src/main/resources/geo/`, CC BY 4.0), so imports don't need a geocoding API. Whether a team is a "school" or "community" team is guessed from its organization name (e.g. "… High School" vs. "4-H Club"); teams can fix this after claiming their listing.

CSV columns (header row required): `name, teamNumber, type, programs, schoolName, city, state, zip, website, minGrade, maxGrade` — `programs` separated by `;`, `type` is `COMMUNITY` or `SCHOOL`, grades are `K`/0–12.

Re-running an import updates existing rows (matched by team number) and **never overwrites a team that has claimed its listing**.

### How the scraper works (`backend/src/main/java/org/readysetrecruit/scrape`)
1. *(optional, needs `BRAVE_API_KEY`)* For unclaimed teams **without** a website, search the web. A result is only accepted if it clearly matches (team number + "robot", or most words of the team name) and isn't a directory/social site. These links are labeled "found automatically" until the team confirms them; if one keeps failing it's dropped.
2. For teams **with** a website: respect `robots.txt`, fetch the homepage with an identifying user agent, and save the title, description, a "join / apply / recruit / tryout / sign up" link (Google Forms etc. score higher) and a `mailto:` contact.
3. Works in batches (`SCRAPER_BATCH_SIZE`, default 100) every `SCRAPER_INTERVAL_MINUTES` (5) while the server is awake, checking `SCRAPER_THREADS` (6) different sites at a time with a pause between requests; sites are rechecked every `SCRAPER_RECHECK_DAYS` (14).

Set `SCRAPER_USER_AGENT` to include a URL or email where site owners can reach you.

---

## API overview

| Method | Path | Who |
|---|---|---|
| POST | `/api/auth/register`, `/api/auth/login` | anyone |
| GET/PUT | `/api/auth/me` | logged in |
| GET | `/api/teams/search?zip=&radius=&grade=&program=&type=&openApplications=&q=` | anyone |
| GET | `/api/teams/{id}`, `/api/teams/{id}/form` | anyone |
| POST | `/api/teams/{id}/claim` | team account |
| GET/PUT | `/api/applications/team/{teamId}` (save draft / submit) | student |
| GET | `/api/applications` · POST `/api/applications/{id}/withdraw` | student |
| GET/POST/PUT | `/api/my/teams[/{id}]` | team |
| GET/PUT | `/api/my/teams/{id}/form` | team |
| GET/PATCH | `/api/my/teams/{id}/applications[/{appId}]` | team |
| GET/POST | `/api/admin/stats`, `/claims`, `/jobs/scrape`, `/jobs/import-frc`, `/jobs/import-ftc`, `/jobs/import-csv` | admin |
| POST | `/api/cron/scrape`, `/api/cron/import` (header `X-Cron-Token`) | GitHub Actions |

ZIP codes are looked up in the bundled GeoNames data; anything missing falls back to the free [Zippopotam.us](https://api.zippopotam.us) API and is cached in the database.

---

## Kids' privacy — read before launching

Students may be under 13, so treat this like a site for minors:
- Sign-up requires students to confirm they're 13+ or that a parent is helping; applications require a parent/guardian email. For under-13 users in the US, COPPA requires verifiable parental consent — talk to someone knowledgeable (many youth programs simply require a parent to create the account) before opening it to younger kids.
- Only the team a student applies to sees their application. Team notes are private to the team.
- Add a privacy policy and terms page, and a way for families to delete their account (not built yet).
- Approve team claims carefully (check the requester's email matches the team's website).

## Ideas for next steps
- Email notifications (new applicant / status change) — e.g. Resend or Brevo free tiers
- Password reset, email verification
- Map view of results
- Database migrations with Flyway instead of `ddl-auto=update` once the schema settles
- Rate limiting on login/register
