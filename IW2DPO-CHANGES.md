This code is in BETA version
Created on Aug 16 2026

This code need the libraries 
https://github.com/maxdpo/humla-iw2dpo

Change requested by IW2DPO Max Italy : end-of-transmission beep (PTT)
Problem

The group uses Mumla on PoC radios in PTT (half-duplex) mode. 
Upon releasing the PTT button, no acoustic signal was transmitted to 
the channel, leaving listening users unaware when the channel became free.

Solution
When the PTT button is released, a short tone ("roger beep") is synthesized 
right before ending the audio encoding. 
It is sent to the channel just like microphone audio: it goes through the 
same encoder (Opus/CELT) and encryption/network stream, making it audible 
to all connected users, not just locally.

Beep characteristics (easily adjustable in libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java):

Frequency: 1500 Hz
Duration: 150 ms
Amplitude: adjustable in-app (see "Beep volume" below), 40% full scale by default (to avoid being overly obtrusive)
Fade-in/out: 10 ms to prevent clicking sounds

Activation / Deactivation
Added a new option under the Audio settings in the app, within the "Push-to-talk settings" section:

End of transmission beep (preference key: release_beep_enabled)
Enabled by default (true)

Visible only when the transmission mode is set to "Push-to-talk"

Beep volume
Added a slider right below the beep toggle, in the same "Push-to-talk settings" section:

Transmission end beep volume (preference key: release_beep_volume)
Range 1-100 (percentage of full scale), default 40, same as the original fixed amplitude
Only enabled when "End of transmission beep" is on
Propagated to the Humla library through a new extra, EXTRAS_RELEASE_BEEP_VOLUME

Automatic connect/reconnect (beta3, revised in beta4)
Problem: on the group's PoC radios, Mumla doesn't log into any saved server on launch --
you have to open the app, wait for the server list, and pick one by hand every time.
Also, when mobile data is briefly unavailable, Mumla can silently drop the connection and
stay offline without anyone noticing.

Solution: a single automatic connect/reconnect toggle, added to General settings, right
after "Start up showing pinned channels":

Automatic connect/reconnect (preference key: autoconnect_enabled)
Off by default, so nothing changes for anyone who doesn't turn it on
When on: as soon as you successfully connect to any saved server, Mumla remembers it as
the "last used server" (preference key: last_server_id, not shown in the UI -- kept up to
date automatically, no server picker needed)
Next time the app is launched (e.g. after the PoC radio is turned off and back on), it
logs into that last used server automatically, no need to open the server list by hand
A watchdog checks every 60 seconds whether that connection is still up -- if not, it
reconnects on its own, no user action needed
While actively retrying, the app keeps its background service in the foreground (with a
"riconnessione automatica in corso" notification) specifically so Android doesn't kill it
before the next 60-second check -- this is why the setting mentions extra battery use
Note: this is a "stay connected no matter what" feature. If you want to disconnect for
good, turn the toggle off first, otherwise the watchdog will reconnect you.

beta4 change: removed the beta3 "Preferred server" dropdown -- the setting now always
targets whichever server you actually used last, tracked automatically, instead of one
you had to pick by hand from a menu.

Modified files


- `libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.java`
  — logica di generazione/trasmissione del beep (metodo
  `encodeReleaseBeep()`), chiamato al rilascio del PTT prima di
  `mEncoder.terminate()`.
- `libraries/humla/src/main/java/se/lublin/humla/HumlaService.java` —
  nuovo extra `EXTRAS_RELEASE_BEEP` per propagare l'impostazione
  dall'app alla libreria Humla.
- `app/src/main/java/se/lublin/mumla/Settings.java` — nuova preferenza
  `PREF_RELEASE_BEEP` / `isReleaseBeepEnabled()`.
- `app/src/main/java/se/lublin/mumla/app/ServerConnectTask.java` — invia
  l'impostazione alla connessione iniziale.
- `app/src/main/java/se/lublin/mumla/service/MumlaService.java` — applica
  il cambio impostazione anche "a caldo" (senza riconnettersi).
- `app/src/main/res/xml/settings_audio.xml`,
  `app/src/main/res/values/preference.xml`,
  `app/src/main/res/values-it/preference.xml` — voce di menu (EN + IT).
- `app/build.gradle` — versionName/versionCode di questa build di test.
- `app/src/beta/res/values/strings_notranslate.xml` — nome app
  "Mumla Beta IW2DPO" per il flavor beta (installabile insieme alla
  versione ufficiale, package `se.lublin.mumla.beta`).
- `.github/workflows/build-iw2dpo-beta.yml` — workflow GitHub Actions per
  compilare l'APK di test automaticamente nel cloud (vedi istruzioni).
- `app/src/main/java/se/lublin/mumla/Settings.java` — nuove preferenze
  `PREF_AUTOCONNECT_ENABLED` / `PREF_LAST_SERVER_ID` (quest'ultima
  aggiornata automaticamente, non è più scelta a mano).
- `app/src/main/res/xml/settings_general.xml` — nuova voce
  "Connessione/riconnessione automatica" in Impostazioni generali (il
  menu "Server preferito" della beta3 è stato rimosso in beta4).
- `app/src/main/java/se/lublin/mumla/app/MumlaActivity.java` — connessione
  automatica all'ultimo server usato subito dopo l'avvio dell'app.
- `app/src/main/java/se/lublin/mumla/service/MumlaService.java` — registra
  l'ultimo server usato ad ogni connessione riuscita; watchdog che
  controlla ogni 60 secondi lo stato della connessione e riconnette da
  solo se necessario; mantiene il servizio in primo piano durante i
  tentativi di riconnessione.

## Versione di questa build

- versionName: `<versione-base>-IW2DPO-autoconnect-beta4` (es.
  `3.7.3-21-g477b337-IW2DPO-autoconnect-beta4[-debug]`)
- Nome app sul dispositivo: **Mumla Beta IW2DPO**
- Package Android (applicationId): `se.lublin.mumla.beta` — può essere
  installata *insieme* alla versione ufficiale di Mumla, senza
  conflitti.

## Nota importante

Questa è una build di test ("beta4"), non rivista né distribuita dal
maintainer ufficiale di Mumla. È pensata solo per il collaudo interno del
gruppo radioamatoriale.
