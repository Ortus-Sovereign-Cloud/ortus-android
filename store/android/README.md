# Ortus on Google Play

What the Play listing needs, kept with the app: the texts in eight languages
(`listing/<language>/`), the data safety answers (`DATA_SAFETY.md`) and the
release workflow (`.github/workflows/release.yml`).

## Once, when the Play developer account exists

1. **Make the upload key** (on your own machine; keep the file and its
   passwords safe, outside the repository):

   ```sh
   keytool -genkeypair -v -keystore ortus-upload.jks -alias upload \
     -keyalg RSA -keysize 4096 -validity 10000 \
     -dname "CN=Ortus, O=<organisation>, C=PL"
   ```

2. **Give it to CI** as repository secrets:

   ```sh
   gh secret set ORTUS_UPLOAD_KEYSTORE_B64 < <(base64 -w0 ortus-upload.jks)
   gh secret set ORTUS_UPLOAD_STORE_PASSWORD
   gh secret set ORTUS_UPLOAD_KEY_ALIAS --body upload
   gh secret set ORTUS_UPLOAD_KEY_PASSWORD
   ```

3. **Create the app** in Play Console: name "Ortus Workspace", default
   language English, app (not game), free. Turn on **Play App Signing**
   (Google keeps the app signing key; ours is only the upload key).

4. **Build a bundle**: Actions → release → Run workflow (from `main`, after
   CI is green). The signed `app-release.aab` is attached to the run.

5. **Upload it**: the first time by hand, to Testing → Internal testing →
   Create release (Play takes API uploads only once an app has a release);
   add testers by email. After that, CI uploads:

   - Play Console → Users and permissions → Invite new users: create a
     service account first in Google Cloud (IAM → Service accounts →
     Create; no roles), make a JSON key for it (Keys → Add key → JSON),
     and invite its email with the app permission **Release to testing
     tracks**.
   - `gh secret set PLAY_SERVICE_ACCOUNT_JSON < key.json`, then delete the
     file from your machine.
   - Actions → release → Run workflow with **Upload to Google Play** on.
     Until the app's first review, choose status `draft` and finish the
     release in Play Console.

6. **App links**: in Play Console → Setup → App signing, copy the
   **SHA-256 of the app signing key** and set it on the installations
   (`ORTUS_ANDROID_CERT_SHA256`, comma-separated with the upload key's),
   so `/.well-known/assetlinks.json` names the Play build and invitation
   and sign-in links open the app.

## The listing

- Texts: `listing/<language>/title.txt` (at most 30 characters),
  `short_description.txt` (at most 80) and `full_description.txt` (at most
  4000), in en, pl, uk, de, fr, ar, es and it. `ci/check.sh` checks the limits.
- Category: Business. Contact email: the organisation's. Privacy policy:
  the installation's privacy page (the app talks only to the
  organisation's own Ortus).
- Content rating: answer the questionnaire as a workplace communication
  app (no violence, no gambling, user-to-user communication within an
  organisation).
- Data safety: see `DATA_SAFETY.md`.
