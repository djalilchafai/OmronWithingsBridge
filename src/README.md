# OmronWithingsBridge v1.6

## Flux simplifié

Au démarrage, l’application ouvre directement CameraX. Dès que SYS / DIA / PULSE sont stables, elle capture automatiquement une photo haute résolution et la relit avec le pipeline photo validé. L’utilisateur n’a plus qu’à vérifier/corriger les trois champs puis toucher **ENREGISTRER**. Après succès, retour automatique au viseur. La galerie et la saisie manuelle restent accessibles depuis le viseur.

Le titre est désormais rendu dans le contenu de l’application (thème NoActionBar) avec gestion explicite des insets système, afin d’éviter qu’il recouvre le premier texte.

Cette version réintroduit la lecture directe, mais **le flux final reste le moteur photo validé de la v1.4**.

## Flux principal : lecture directe

1. `Lecture directe` ouvre CameraX.
2. `ImageAnalysis` analyse périodiquement le flux complet avec le même `AutoOmronLocator` et le même `OmronTemplateReader` que la photo.
3. SYS / DIA / PULSE s'affichent en direct avec `?` puis `✓` lorsqu'ils se stabilisent.
4. Après trois lectures complètes consécutives identiques, l'application déclenche automatiquement un vrai `ImageCapture` JPEG.
5. Cette photo haute résolution est relue avec **exactement le pipeline photo v1.4** : repères SYS/DIA/PULSE → cadrage automatique → rectification 600×900 → templates Omron.
6. Seules les valeurs issues de cette photo finale sont proposées sur l'écran de confirmation.
7. L'utilisateur peut modifier manuellement SYS / DIA / PULSE avant l'écriture dans Santé Connect.

Le bouton `Capturer et analyser maintenant` permet aussi de déclencher immédiatement la photo finale sans attendre le consensus live.

## Modes de secours

- `Prendre une photo` : application caméra du téléphone, puis analyse automatique v1.4.
- `Choisir une photo existante` : galerie, puis analyse automatique v1.4.
- `Saisie manuelle`.

## Choix techniques importants

- La détection live porte sur **toute la frame**, le rectangle blanc n'est qu'un guide utilisateur.
- `ImageAnalysis` est demandé autour de 1280×960 avec `STRATEGY_KEEP_ONLY_LATEST`.
- L'analyse coûteuse est limitée à environ une frame toutes les 650 ms.
- Le flash CameraX est désactivé pour éviter de créer un reflet direct sur le LCD.
- Le moteur de reconnaissance et ses paramètres géométriques ne sont pas retunés : ils restent ceux validés en v1.4.
- Le live n'écrit jamais directement dans Santé Connect : une photo finale est toujours relue avant confirmation.

## Confidentialité

L'analyse est locale. ML Kit utilise le modèle Latin embarqué uniquement pour les repères imprimés `SYS`, `DIA`, `PULSE`. L'application n'envoie ni photo ni donnée de santé vers un serveur. Elle demande la caméra pour le mode direct et uniquement les droits d'écriture Blood Pressure / Heart Rate dans Santé Connect.

## Compilation

```bash
cp local.properties.example local.properties
# normalement : sdk.dir=/usr/lib/android-sdk

gradle wrapper --gradle-version 8.13
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`applicationId` reste `fr.omronbridge.app`.

## Régression du moteur photo

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install opencv-python numpy
python tools/check_auto_samples.py
```

Résultat attendu :

```text
auto_geometry_136_90_61.png: got=(136, 90, 61), expected=(136, 90, 61)
auto_geometry_111_75_56.png: got=(111, 75, 56), expected=(111, 75, 56)
```

## Diagnostic Android

```bash
adb logcat -s OmronAutoCrop OmronContour
```

## Fichiers principaux

- `MainActivity.kt` : CameraX live, consensus, capture finale, photo/galerie, correction et Santé Connect.
- `OmronImagePipeline.kt` : rectification headless utilisée par CameraX et fondée sur la géométrie v1.4.
- `TemporalConsensus.kt` : consensus glissant du mode live.
- `GuideOverlayView.kt` : guide visuel du viseur ; il n'impose pas le crop.
- `AutoOmronLocator.kt` : localisation automatique du LCD par SYS / DIA / PULSE.
- `OmronTemplateReader.kt` : lecture des chiffres Omron.
- `LcdOcrPreprocessor.kt` : normalisation locale / reflets.
- `HealthConnectWriter.kt` : écriture dans Santé Connect.