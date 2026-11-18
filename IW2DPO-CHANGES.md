What to do next :
- DONE set as default debouncing as active and at 100 mSec
- DONE reduce the default local tones at 7% or better change the whole range
- DONE create a DOC folder for general documentation
- DONE create a POP UP WINDOWS if PTT KEY is not setted
- add server stored in a file for avoid add server every APP Update
- change the version to Version 16-11-2026-4 > 5




Version 18-11-2026-2 (branch "master")
Side menu: "VER : 18-11-2026-2"
KEYLOCK screen: "KEYLOCK" text removed from the top box (it was cut on the
radio screen). The top box now shows only the typed digits (● ● _ _) and is
smaller, so the keypad buttons are bigger. File: KeylockController.java
-----------------------------------------------

Version 18-11-2026-1 (branch "master")
Side menu: "VER : 18-11-2026-1"
Settings > General:
 - "Screen lock (KEYLOCK)" default now OFF (was Auto)
 - new "KEYLOCK CODE": 4-digit unlock code (default 1234); numeric keyboard,
   only exactly 4 digits accepted. Used from the next time the screen locks.
Files: KeylockController.java, GeneralSettingsFragment.java, Settings.java,
 settings_general.xml, strings_notranslate.xml
-----------------------------------------------

Version 17-11-2026-3 (branch "master")
Side menu: "VER : 17-11-2026-3"
KEYLOCK screen for POC radios with touch screen:
 - "KEYLOCK" title + 3x4 keypad (1-9, #, 0, *) covering the whole Mumla screen
 - locked at app start and after 1 minute without touches on the screen
 - unlocked with code 1234 ("*" or "#" clear what was typed; wrong code = toast)
 - while locked: PTT and volume keys work; touches, Back and Menu are blocked
 - Settings > General > "Screen lock (KEYLOCK)": Auto (default, only radios
   that report a touch screen) / Always on / Off
Files: app/KeylockController.java (new), MumlaActivity.java, Settings.java,
 settings_general.xml, strings_notranslate.xml
-----------------------------------------------

Version 17-11-2026-2 (branch "tones")
Side menu: "VER : 17-11-2026-2"
NOT READY TO TALK tone (300 Hz) level doubled again: 4 x "Local tones level"
(+12 dB; e.g. 7 % -> 28 %), capped at full scale (no clipping).
File: LocalTonePlayer.java (NOT_READY_GAIN = 4.0)
-----------------------------------------------

Version 17-11-2026-1 (branch "tones")
Side menu: "VER : 17-11-2026-1"
NOT READY TO TALK tone (300 Hz, 1 s) level doubled (x2 = +6 dB) compared
to "Local tones level" (e.g. 7 % -> 14 %), capped at full scale (no clipping).
READY TO TALK tones unchanged. File: LocalTonePlayer.java
-----------------------------------------------

Version 16-11-2026-6 (branch "tones")
Side menu: "VER : 16-11-2026-6"
Settings > Audio reordered (cosmetic). First items, in this order:
 Push-to-talk key, Vol-up-key, Vol-down-key, Local tones,
 Local tones level, Debouncing. Then the other settings as before.
 "Push-to-talk settings" heading removed; "Volume keys" section removed
 (its two keys are now at the top). File: settings_audio.xml
-----------------------------------------------

Version 16-11-2026-5 (branch "tones")
Side menu: "VER : 16-11-2026-5"
Servers no longer lost when a new APP version is installed:
 1) FIXED SIGNING KEY for the GitHub Actions debug/beta builds
    (app/iw2dpo-debug.keystore + signingConfigs.debug in app/build.gradle).
    Before, every build had a new random key: Android refused the update, the
    app had to be uninstalled and ALL data (servers, PTT key, settings) was lost.
    Now new versions install over the old one and keep all data.
    NOTE: the FIRST install of this version still needs one uninstall.
 2) Favourites menu (3 dots): "Export servers" / "Import servers"
    JSON file (default mumla-servers.json, e.g. in Download) with label,
    address, port, username, password. Import skips servers already present.
Files: app/build.gradle, app/iw2dpo-debug.keystore (new),
 FavouriteServerListFragment.java, fragment_server_list.xml, strings_notranslate.xml
-----------------------------------------------

Version 16-11-2026-4 (branch "tones")
Side menu: "VER : 16-11-2026-4"
Settings > Audio defaults (new installations / after "clear data"):
 - Debouncing: ON, 100 ms (was OFF)
 - Local tones level: 7 % (was 50 %)
Popup "Set PTT KEY first" at Mumla start when the Push-to-talk key is not set
 (buttons: OK, Settings).
Files: Settings.java, settings_audio.xml, MumlaActivity.java, strings_notranslate.xml
-----------------------------------------------

Version 16-11-2026-3 (branch "tones")
Side menu: "VER : 16-11-2026-3"
Mic mute after the "ready to talk" tones back to the original 200 ms
(was 100 ms in 16-11-2026-2). File: MumlaService.java
-----------------------------------------------

Version 16-11-2026-2 (branch "tones")
Side menu: "VER : 16-11-2026-2"
Mic mute after the "ready to talk" tones restored, reduced to 100 ms
(was 200 ms in 15-11-2026-3, 0 ms in 16-11-2026-1). File: MumlaService.java
-----------------------------------------------

Version 16-11-2026-1 (branch "tones")
Side menu: "VER : 16-11-2026-1"
Removed the extra 200 ms mic mute after the "ready to talk" tones
(see DOC/PTT_Sequence.pdf): the mic gate now reopens as soon as the
beep has finished on the loudspeaker. File: MumlaService.java
-----------------------------------------------

Version 15-11-2026-3 (branch "tones")
Side menu: "VER : 15-11-2026-3"
FIX: local PTT beep was still transmitted to the channel.
 Cause: Humla keeps the mic AudioRecord running between transmissions,
 so buffered mic audio was sent when TX started (delaying TX did not help).
 Fix: new MIC GATE in humla-iw2dpo (branch "tones"):
  - humla: audio/MicGate.java (new), protocol/AudioHandler.java:
    while the gate is closed every mic frame is replaced with SILENCE
  - mumla: gate closed before the beep starts, reopened 200 ms after the
    beep has really left the loudspeaker (AudioTrack timestamp)
  - TX starts immediately again (as before 15-11-2026-2)
 libraries/humla submodule updated to humla-iw2dpo branch "tones"
-----------------------------------------------

Version 15-11-2026-2 (branch "tones")
Side menu: "VER : 15-11-2026-2"
Mic TX blocked while the local PTT tone is playing:
 TX (mic) starts only when the local beep has finished on the loudspeaker
 (+80 ms), so the beep is no longer transmitted to the channel.
 PTT released during the beep = nothing transmitted.
Files: LocalTonePlayer.java, MumlaService.java, strings_notranslate.xml
-----------------------------------------------

Version 15-11-2026-1 (branch "tones")
Side menu: "VER : 15-11-2026-1"
Local tones on PTT press (radio loudspeaker only, not transmitted):
 READY TO TALK (connected to a server):
   1568 Hz 40 ms, 1046 Hz 40 ms, 1568 Hz 40 ms, 1318 Hz 40 ms
 NOT READY TO TALK (no server / server unavailable):
   300 Hz 1000 ms
New in Settings > Audio (PTT section):
 "Local tones" checkbox (ON by default)
 "Local tones level" 1-100 % (default 50 %)
Files:
 app/src/main/java/se/lublin/mumla/service/LocalTonePlayer.java (new)
 app/src/main/java/se/lublin/mumla/service/MumlaService.java
 app/src/main/java/se/lublin/mumla/Settings.java
 app/src/main/res/xml/settings_audio.xml
 app/src/main/res/values/preference.xml
 app/src/main/res/values-it/preference.xml
humla-iw2dpo: no changes needed
-----------------------------------------------

Version 13-11-2026-3: "Settings" moved to top of side menu,
"Audio" moved to top of Settings
Fix: screen ON 1 minute (was 2: removed ON_AFTER_RELEASE)
Side menu header: title "Mumla" + row "VER : 13-11-2026-2"
Wake display on RX/PTT: screen turns ON when someone talks
Stay awake fix: checked = screen always ON (no timeout),
unchecked (default) = screen ON for 1 minute after last activity
debouncing for PTT key menu added
Hide PTT button ON by default
Input pre-processing OFF by default
When STAY AWAKE is OFF timer = 5 min
Defaults: TX end beep SINGLE TONE, mic quality 20000 bps
Autoconnect ON by default, short TX end beep description
Add TX end beep sound menu, rename to TX end beep
Default transmit mode: Push-to-talk
Update humla: DTMF 0 roger beep
Added programmable Vol-up, Vol Down keys

Last mod after Hytera P50 qualified

Default transmit mode: Push-to-talk
Nov 6 2026
-----------------------------------------------

Added programmable Vol-up-key Vol-Down-key
Nov 5 2026
-----------------------------------------------

19/10/2026
Error in copy and paste
files for remove FTP client
----------------------------------------------


18/10/2026
Ha funzionato !
Questa versione sospende il client FTP
Per riattivare l’FTP in futuro basta mettere 
FTP_ENABLED = true in FtpLogUploader.java e ricompilare. 
Se vuoi anche il file di prova all’avvio, 
metti FTP_STARTUP_TEST = true in MumlaApplication.java.


--------------------------------------------------
18/10/2026
Tentativo risoluzione BUG
Working in progress

------------------------------------------------------

17/10/2026
prompt
Log salvato dall’app stessa (quello che chiedevi). 
Ho modificato il file MumlaApplication.java del branch Android12. 
Ora l’app scrive un file di testo ogni volta che si chiude per errore. 
Lo trovi in Download/MumlaLog/ (visibile con qualsiasi file manager sulla radio) 
e in copia anche in Android/data/se.lublin.mumla.beta/files/MumlaLog/.
C'e' pero' un problema : su questo apparato, un HYTERA P50 pro
è impossibile leggere il file di log il sistema è protetto.
Servirebbe quindi che il file di log venga copiato su in server
esterno per essere analizzato.

L'idea che ho avuto è quella di chiedeti di inserire nel
codice un client FTP e copiare il file di log
in un server FTP di cui sotto ti passo le credenziali.

Il server FTP sarà ubicato nella stessa subnet
dell'IP dell'apparato che è connesso in WI-FI

Ti do anche i dati della repository di GITHUB
dove andare a prendere le informazioni e dove
è depositato il lavoro fatto fino ad ora.

IP: 192.168.113.253
User: Max
Psw: Massimo2026

E' un IP interno per cui non raggiungibile
da nessuno.

https://github.com/maxdpo/mumla-iw2dpo/tree/Android12

ANSWER :

Ho integrato l’invio FTP partendo dal codice attuale 
del branch Android12, 
cioè dal tuo commit “Installed a crash logger”. 
Il client FTP è scritto in Java standard, senza librerie esterne, 
quindi non aggiunge dipendenze alla build.

Come funziona. Ogni log viene salvato in locale e poi inviato 
al server 192.168.113.253 nella cartella MumlaLog. 
Al momento del crash l’app prova a inviare il file subito, 
aspettando al massimo 10 secondi. Se non ci riesce, 
lo reinvia al successivo avvio: i file già inviati non vengono rispediti.

Ad ogni avvio l’app invia anche un piccolo file di prova mumla_start_….txt, 
con il modello della radio e gli IP Wi-Fi e cellulare. 
Se questo file compare sul server, il collegamento funziona. 
Quando non ti serve più, metti FTP_STARTUP_TEST = false in MumlaApplication.java.

Se la radio ha attivi anche i dati mobili, il codice forza 
comunque l’uso della rete Wi-Fi, altrimenti Android potrebbe 
cercare il server sulla rete cellulare.

Cosa caricare su GitHub (branch Android12):

FtpLogUploader.java è un file nuovo. 
Vai in app/src/main/java/se/lublin/mumla/app/, 
poi Add file → Create new file, 
chiamalo FtpLogUploader.java e incolla il contenuto.
MumlaApplication.java, nella stessa cartella: 
aprilo con la matita ✏️ e sostituisci tutto il contenuto.
AndroidManifest.xml, in app/src/main/: sostituisci tutto il contenuto. 
L’unica modifica è il permesso ACCESS_NETWORK_STATE, 
necessario per trovare la rete Wi-Fi.
Fai commit sul branch Android12, rilancia il workflow e installa il nuovo APK.

Sul server FTP controlla tre cose:

FTP in chiaro consentito. 
Se usi FileZilla Server, le versioni recenti richiedono TLS di default. 
Va abilitato l’FTP semplice, altrimenti il login viene rifiutato.
Modalità passiva e firewall. Devono essere aperte la porta 21 
e il range di porte passive del server (su Windows, controlla Windows Defender Firewall).
Permessi dell’utente Max. Deve poter scrivere, e possibilmente creare la cartella MumlaLog. 
Se non può crearla, i file finiscono nella cartella principale.

La password è scritta nel codice e quindi leggibile nell’APK e nel repository, 
che è pubblico. Usa questo account solo per i log, con accesso limitato a quella cartella, 
e cambia la password quando hai finito i test.

Non ho potuto compilare qui. Se la build fallisce, mandami lo screenshot 
dell’errore. Se invece la build va ma sul server non arriva nemmeno il file 
mumla_start, dimmelo e controlliamo la rete.


--------------------------------------------------------
17/10/2026
Ho scelto questa soluzione che hai citato tu.
Te la riscrivo qui sotto :
-----------------------------------------------------
12/10/2026
Installato un crash logger
app/src/main/java/se/lublin/mumla/app/MumlaApplication.java

-----------------------------------------------------
12/10/2026
Android12 Branck
This has been created for solve issues
in Android 12 POC






Things to do :

Qualità audio microfono : default 40000 reduce to 20000
TX Mode : VOX (PTT still not work)
Nascondi Pulsante Push To Talk : disable





C:\mumla-iw2dpo\app\src\main\res\xml\settings_audio.xml

Changed :
input_quality : 16000
release_beep_volume : default 7
release_beep_volume range : 1-30%


"C:\mumla-iw2dpo\app\src\main\java\se\lublin\mumla\Settings.java"

Changed the string :
ARRAY_INPUT_METHOD_VOICE
to
ARRAY_INPUT_METHOD_PTT

This code is BETA4_1


Blocked automatic APK compile on every push
"C:\mumla-iw2dpo\.github\workflows\build-iw2dpo-beta.yml"

How to trigger a build manually afterward

Repo 
→ Actions tab 
→ select the workflow in the left sidebar 
→ click on the left "Build IW2DPO beta APK......"
→ after the click above on the right you should see "Run Workflow"
→ click Run workflow (dropdown appears if workflow_dispatch is set) 
→ choose branch 
→ Run workflow.






This code is in BETA version
created Aug 2026

This code needs the libraries:
https://github.com/maxdpo/humla-iw2dpo

Important Note
This is a test build ("beta4"),
neither reviewed nor distributed by 
the official Mumla maintainer.
It is intended solely for internal 
testing by the amateur radio group.

Beta 1: Added beep upon PTT release
(very useful) 1500 Hz x 150 ms
16/08/2026

Beta 2: Added beep level adjustment
It is recommended to set it to 10%
17/08/2026

Beta 3: Added automatic connection
to a default server
18/08/2026

Beta 4: Added automatic connection
to the last used server and automatic
reconnection in case of disconnection
19/08/2026

Version of this build
versionName: <base-version>-IW2DPO-autoconnect-beta4 (e.g.,
3.7.3-21-g477b337-IW2DPO-autoconnect-beta4[-debug])
App name on the device: Mumla Beta IW2DPO
Android Package (applicationId): se.lublin.mumla.beta — can be
installed alongside the official version of Mumla, without
conflicts.
