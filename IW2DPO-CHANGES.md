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
