# SkyLyrics for Minecraft 26.2 (Fabric, client-side)

Floating Spotify lyrics in the sky. Needs Fabric Loader 0.19.3+, Fabric API 0.155.0+26.2, Java 25.

## Getting the .jar (pick one)
A) No Java needed: create a GitHub repo, upload this whole folder (keep the .github folder), open the
   Actions tab, wait for "build" to finish, download the "skylyrics-jar" artifact, unzip it.
B) Locally: install JDK 25 + Gradle 9.5 (or copy gradlew + gradle/ from the Fabric example mod), then
   run `gradle build`. The jar is in build/libs/skylyrics-1.0.0.jar.

Put the jar in your mods folder next to fabric-api.

## Spotify setup (once)
1. https://developer.spotify.com/dashboard -> Create app, tick "Web API".
2. Add Redirect URI exactly:  http://127.0.0.1:8888/callback
3. Copy the Client ID, then in Minecraft:
       /lyrics setclientid <client id>
       /lyrics login        (click the green link in chat, approve in the browser)

Commands: /lyrics login | logout | status | toggle | offset <ms>
