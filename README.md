# Fluide Player — lecteur IPTV / multimédia pour Android TV et mobile

Lecteur **neutre** (aucune chaîne fournie) pour les comptes **Xtream Codes**, les playlists M3U et les
guides XMLTV de **votre propre service**. TV en direct, **films**, **séries**, **replay**, guide des
programmes, contrôle parental, reprise de lecture — avec un moteur de lecture où le gel d'image ne se voit
pas, même sur des flux IPTV de qualité médiocre et des box à 1 Go de RAM.

- Android 7.0+ (minSdk 24), cible Android 16 (targetSdk 36), compileSdk 37
- Un seul APK : Android TV / Google TV / Fire TV (télécommande) et téléphones / tablettes (tactile)
- Kotlin 2.4.20, AGP 9.3.1 (Kotlin intégré), Jetpack Compose (BOM 2026.09), **Media3 / ExoPlayer 1.11.1**

---

## 1. Compiler et installer

Prérequis : Android Studio récent compatible AGP 9.3, JDK 17 ou plus, SDK Android 37.

```bash
./gradlew :core:test            # 59 tests du moteur (parseurs, anti-freeze, zapping)
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug    # APK de debug
./gradlew :app:assembleRelease  # APK optimisé (R8), signé avec la clé de debug
adb install -r app/build/outputs/apk/release/app-release.apk
```

Sur une box Android TV : activer les options développeur → « Débogage ADB » (ou « Débogage réseau »),
puis `adb connect <ip-de-la-box>` avant `adb install`.

> **Avant toute diffusion** : remplacer `signingConfig = signingConfigs.getByName("debug")` dans
> `app/build.gradle.kts` par votre propre clé de signature.

### Compilation automatique (GitHub Actions)

Chaque push sur `main` compile le projet sur les serveurs de GitHub (SDK Android officiel), lance les tests
puis publie les APK :

- branche **`apk`** du dépôt : `fluide-player-release.apk` (optimisé), `fluide-player-debug.apk`,
  `build-log.txt` et `STATUS.txt` (résultat du build) ;
- onglet **Actions** → dernier build → *Artifacts*.

Les builds des branches de travail `claude/*` sont publiés de la même façon sur la branche **`apk-preview`**.

Le dépôt étant public, aucune clé de signature n'y est stockée : chaque build est signé avec une clé de test
générée à la volée. Pour installer un nouvel APK par-dessus l'ancien, désinstallez d'abord l'ancien
(ou placez votre propre clé en `keystore/fluide-debug.jks`, hors dépôt public).

Premier lancement : écran **Playlists** → onglet **Xtream Codes** (URL du serveur, utilisateur, mot de
passe) ou **URL M3U** / **Fichier**, cocher l'attestation de droits, « Se connecter » / « Ajouter l'URL ».
L'accueil s'ouvre dès que l'import est terminé. Un lien `get.php?username=…&password=…` collé dans l'onglet
M3U est reconnu et importé comme compte Xtream (films et séries détaillés).

---

## Fonctions (parité avec IBO Player)

La liste des fonctions a été établie à partir d'un inventaire **statique** de l'APK de référence
`ibop.apk` (com.ibostore.meplayerib4k 230.2) fait avec [REA](https://github.com/morluto/rea)
(`inspect-android-package`, `decode-android-resources`, inventaire des écrans et des appels Xtream) sur un
runner GitHub (workflow manuel `Analyze reference APK`). **Aucun code, aucune image ni aucun texte de cette
application n'est repris** : tout est réécrit pour Fluide Player.

| Fonction de la référence | Dans Fluide Player |
|---|---|
| Accueil Live TV / Films / Séries / Replay, playlists, réglages, actualiser, quitter | Écran d'accueil à tuiles, horloge, ligne compte (expiration, statut, connexions) et alerte d'expiration à 7 jours |
| « Add M3U Xtream » / « Add M3U Playlist », gestion de plusieurs playlists | Comptes Xtream Codes (vérification du compte), URL M3U, fichier ; activer / actualiser / supprimer ; format TS ou HLS |
| Live TV : catégories, chaînes, recherche, favoris (appui long), historique | Catégories, liste virtualisée, recherche instantanée, favoris (Menu / appui long), **Récents**, aperçu vidéo + guide de la chaîne |
| EPG (`xmltv.php`, `get_short_epg`) et guide des programmes | Guide XMLTV importé + `get_short_epg` à la demande ; écran **Guide TV** jour par jour |
| Catch-Up (`/timeshift/`) | **Replay** des programmes passés (Xtream `xc`, `shift`, `append`, `flussonic`, modèles `catchup-source`) |
| Films : catégories, tri (A-Z, Z-A, note, ajouts récents), fiche (genre, réalisateur, acteurs, durée, note, bande-annonce) | Grille d'affiches, tri, recherche, favoris, « Continuer à regarder », « Ajouts récents », fiche détaillée (`get_vod_info`), bande-annonce YouTube |
| Séries : saisons et épisodes, « dernier épisode regardé », « Up Next » | Fiche série (`get_series_info`), saisons, épisodes, reprise, **épisode suivant** automatique (compte à rebours) |
| « Reprendre là où vous vous êtes arrêté » / « Depuis le début » | Points de reprise films et épisodes, conservés après actualisation |
| Pistes audio, sous-titres, format d'image, informations média | Panneau d'options (▼ / Menu) : audio, sous-titres, Adapter / Étirer / Zoom, statistiques |
| Contrôle parental (code à 4 chiffres), gérer / masquer les catégories | Code parental (0000 par défaut), catégories adultes verrouillées, chaque catégorie visible / verrouillée / masquée |
| Lecteur externe, démarrage automatique, vider le cache | « Ouvrir avec… » (VLC, MX…), lancement au démarrage, vider le cache images, effacer l'historique |

### Au-delà de la référence

| Fonction | Détail |
|---|---|
| Identité propre | Logo Fluide (triangle de lecture traversé d'une vague, bleu → cyan), icône, bannière Android TV, thème « verre » bleu nuit |
| Accueil personnalisé | Bannière à la une, « Continuer à regarder », chaînes favorites et récentes, films et séries ajoutés récemment |
| Recherche globale | Un seul champ pour les chaînes, les films et les séries, résultats surlignés |
| Image dans l'image | Sur téléphone et tablette, quitter un flux en plein écran le garde dans une petite fenêtre |
| Langues | Français, anglais et arabe (de droite à gauche), au choix dans les réglages ou dans les réglages Android (13+) |
| Fiches enrichies | Fond flou, puces genre / année / durée / note, saisons en pastilles, épisodes avec progression |

**Volontairement non repris** : le système d'activation payant de la référence (adresse MAC, « device key »,
code d'activation, abonnement, envoi des playlists par leur site), leurs serveurs, l'émulation de boîtier
MAG / portail Stalker, les lecteurs VLC / IJK embarqués (Fluide garde son moteur Media3 anti-gel), la météo
et la grille d'applications du lanceur.

---

## 2. Organisation du projet

```
core/   Kotlin pur (aucune dépendance Android) — prêt à devenir le module commun KMP
  io/        CharReader, LineReader (lecture en flux, buffers réutilisés)
  m3u/       M3uParser en flux (sans regex, 500 000 entrées max, lignes plafonnées), StreamKind
  xmltv/     XmltvParser en flux (scanner XML maison, entités, CDATA, fenêtre de temps, langue)
  playback/  StallDetector, RecoveryLadder, AntiStallSpeedPolicy, StabilityProfile, SourceHealthTracker
  zap/       ZapGate, ChannelNumberInput, PreloadPlanner
  net/       Backoff (jitter), CircuitBreaker, HttpFailure
  device/    DeviceClassifier (classes A/B/C)   display/ DisplayModeSelector (AFR)
  text/      SearchNormalizer (accents, ponctuation)

app/    Application Android
  playback/  PlaybackController (moteur anti-freeze), PlayerFactory, MediaSourceBuilder,
             BufferAwareLiveSpeedControl, IptvLoadErrorPolicy, FrameRateEstimator, SourceHealthStore
  net/       HttpStack (3 clients OkHttp par priorité), CachingDns, ManifestCache
  data/      FluideDatabase (SQLite WAL + FTS4), ChannelRepository, SourceManager,
             importer/ (PlaylistImporter, EpgImporter, Downloader, TextInput), secure/UrlCipher
  device/    DeviceProfiler, DisplayModeController (AFR)
  ui/        Compose : FluideRoot, guide/, player/ (surface + overlays), sources/, MainViewModel, RowPager
```

---

## 3. Le moteur anti-freeze (ce qui distingue ce lecteur)

| Étape | Ce qui se passe | Où |
|---|---|---|
| Détection | Toutes les 500 ms : position, images rendues/perdues, octets reçus, sous-alimentations audio. 6 signaux (position figée 1,5 s, image figée avec son 2 s, chargement > 4 s, réseau muet 3 s, audio affamé, > 5 % d'images perdues) | `StallDetector` + `PlaybackController.tick()` |
| Échelle de récupération | N1 re-préparation (connexions mortes purgées, décodeur neuf si l'image est figée) → N2 qualité réduite / profil plus sûr → N3 **URL de secours** de la même chaîne (même tvg-id ou même nom sans « HD/FHD/backup ») ou redémarrage complet → N4 « Signal perdu » avec nouvelles tentatives espacées (5 s → 60 s), jamais de boucle qui martèle le serveur | `RecoveryLadder` |
| Coussin invisible | Quand le tampon est bas, lecture à 0,93–0,97x (hauteur du son préservée) : le tampon se remplit au lieu de se vider. Sur les flux live gérés par Media3 : plafond appliqué à son propre contrôle de vitesse ; sur les MPEG-TS HTTP : vitesse pilotée par l'app | `AntiStallSpeedPolicy`, `BufferAwareLiveSpeedControl`, `governSpeed()` |
| Apprentissage par serveur | Chaque serveur reçoit un niveau STABLE / UNSTABLE / HOSTILE appris (et mémorisé entre deux lancements) : plus de tampon, plus loin du direct, démarrage en basse qualité pour les serveurs difficiles | `SourceHealthTracker`, `StabilityProfile` |
| Gel non visible | La dernière image reste affichée pendant une récupération ou un zap (pas d'écran noir) ; le spinner n'apparaît qu'après 600 ms | `PlayerSurface`, `WaitingIndicator` |
| Cas IPTV réels | Connexion TS fermée par le serveur → réouverture immédiate ; sortie de la fenêtre live → retour au direct silencieux ; URL sans extension → bascule TS ↔ HLS ; 401/403 → un seul nouvel essai propre ; passthrough refusé par la chaîne HDMI → bascule PCM ; tunneling défaillant → désactivé | `PlaybackController.handleError()` |
| Filet de sécurité Media3 | La détection « stuck player » de Media3 1.11 reste active en dernier recours (45 s) | `PlayerFactory` |

Réglages par niveau (tampon de démarrage / reprise / min / max, en ms) : STABLE 1000 / 2500 / 15000 / 30000,
UNSTABLE 1500 / 4000 / 25000 / 50000, HOSTILE 2500 / 6000 / 40000 / 60000.

## 4. Zapping

- **Premier appui immédiat**, rafale d'appuis : chargement seulement si la chaîne reste affichée 300 ms
  (pas de 10 flux ouverts/fermés, ce qui bloque les comptes limités à 1 connexion).
- Bandeau instantané (nom, logo, programme en cours/suivant) avant même que le flux charge.
- Décodeur réutilisé d'une chaîne à l'autre (même instance ExoPlayer), connexions TLS réutilisées,
  **DNS mis en cache** (5 min, réponse périmée réutilisée si le DNS tombe, **IPv4 en premier** pour éviter
  les délais d'IPv6 cassé).
- Chaînes voisines préparées après 2 s (DNS toujours ; playlists HLS/DASH en mémoire si la source autorise
  plus d'une connexion — réglable par source).
- Saisie du numéro de chaîne avec validation anticipée (« 7 » part tout de suite s'il n'existe pas de 70–79),
  touche LAST pour la chaîne précédente.
- Flags MPEG-TS : démarrage sur image non-IDR et détection des unités d'accès sans AUD → démarrage plus rapide
  et moins d'images corrompues sur les flux IPTV.

## 5. Grosses playlists et guides

- Import en flux (téléchargement → décompression gzip auto → détection UTF-8 / Windows-1252 → parseur → SQLite),
  mémoire constante quelle que soit la taille. Thread en priorité basse qui **se met en pause quand le tampon
  de lecture passe sous 5 s**.
- Import dans une table de transit puis bascule atomique : la liste visible n'est jamais à moitié importée.
- Requêtes conditionnelles (ETag / Last-Modified) : une playlist inchangée n'est pas ré-importée.
- Liste virtualisée : seuls des identifiants (LongArray) et des pages de 40 lignes sont en mémoire —
  100 000 chaînes défilent comme 100.
- Recherche instantanée par préfixe (FTS4, accents et ponctuation ignorés). Favoris conservés après mise à jour.
- URL chiffrées au repos (AES-256-GCM, clé enveloppée par l'Android Keystore).

## 6. Télécommande

| Touche | TV en direct (plein écran) | Film, épisode, replay |
|---|---|---|
| Haut / CH+ , Bas / CH− | Chaîne suivante / précédente | Haut : barre de lecture · Bas : options |
| OK | Liste des chaînes (sur une erreur : réessayer) | Pause / lecture (fin d'épisode : épisode suivant) |
| Gauche / Droite | Liste des chaînes / bandeau d'information | −10 s / +10 s (maintenu : 30 s puis 60 s) |
| ⏪ / ⏩ | — | −30 s / +30 s |
| 0–9 | Numéro de chaîne | — |
| LAST | Chaîne précédente | — |
| MENU | Options : audio, sous-titres, format d'image, informations média | idem |
| ⏭ | — | Épisode suivant |
| Lecture/Pause | Pause (un direct en pause depuis > 30 s reprend au direct) | Pause |
| Retour | Retour à la liste des chaînes, puis à l'accueil | Retour à la fiche (la position est enregistrée) |

Dans la TV en direct : un premier OK lance la chaîne dans l'aperçu, un second OK la met en plein écran.
Dans les listes : Menu ou appui long sur OK = favori. Au tactile : toucher l'image ouvre la liste (direct)
ou la barre de lecture (vidéo). Retour sur l'accueil : quitter.

---

## 7. Ce qui a été vérifié — et ce qui ne l'a pas été

- `:core` : **59 tests unitaires** (parseurs M3U/XMLTV, détection de gel, échelle de récupération,
  vitesse anti-gel, apprentissage des serveurs, zapping, AFR…) — tous passent.
- `:app` : tout le code Kotlin a été **compilé jusqu'au bytecode** contre les **sources réelles de
  Media3 1.11.1** et un jar du framework Android 36 (Compose, AndroidX, OkHttp et Coil via des signatures
  reproduites à la main) ; 3 tests unitaires passent.
- **Non fait ici** : la compilation Gradle complète et l'exécution sur un appareil (pas de SDK Android ni
  d'accès Maven dans l'environnement de génération). La première compilation dans Android Studio peut
  signaler de petits écarts d'API Compose ; ils se corrigent en quelques minutes.

## 8. Limites connues et suite

- Formats : tout ce que Media3 lit (HLS, DASH, MPEG-TS, MP4, MKV, RTSP, UDP). Pas de libVLC.
  Pour l'audio MP2 / AC-3 sur les box sans décodeur : ajouter l'extension FFmpeg de Media3
  (`decoder_ffmpeg`, à compiler avec le NDK) — elle est déjà activée par `EXTENSION_RENDERER_MODE_ON`.
- Pas de portail Stalker / MAG (connexion par adresse MAC).
- Pas encore : enregistrement, multi-écran.
- Préchargement « lecteur complet » d'une chaîne voisine (2ᵉ décodeur) non activé : sur IPTV en direct il
  coûte une connexion permanente ; le `DefaultPreloadManager` de Media3 a été écarté pour le live (données
  préchargées périmées). Le préchargement DNS + playlist est en place.
- Multiplateforme : `:core` est du Kotlin pur sans I/O JVM dans son API, prêt à devenir le `commonMain`
  d'un module KMP (iOS / tvOS, Tizen et webOS via un portage).

## 9. Écarts par rapport au document d'architecture

- **Media3 1.11** : les valeurs par défaut de `DefaultLoadControl` sont 1000 ms (démarrage) / 2000 ms
  (après coupure) pour un tampon de 50 s, avec la priorité au temps désactivée par défaut — le projet
  fixe ses propres valeurs par niveau de stabilité (section 3).
- **FTS4 au lieu de FTS5** pour la recherche : FTS5 n'est pas garanti dans le SQLite d'Android.
- **OkHttp 4.12** (et non 5.x) : c'est la version avec laquelle Media3 1.11 et Coil 2 sont compilés et testés.

## 10. Cadre légal

Lecteur neutre : aucune chaîne, playlist ou source n'est fournie ni suggérée. L'ajout d'une source exige
une attestation de droits. Aucun contournement (ni de DRM — le Widevine éventuel passe par la plateforme —,
ni de blocage). Licences des dépendances : Apache 2.0 (AndroidX, Media3, OkHttp, Coil), Kotlin (Apache 2.0).
