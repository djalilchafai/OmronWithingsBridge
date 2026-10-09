# Changelog

## 1.6.0
- CameraX devient l’écran de démarrage normal : plus d’écran d’accueil dans le flux courant.
- Capture haute résolution automatique dès que les trois valeurs sont stables.
- Confirmation compacte avec SYS / DIA / PULSE éditables côte à côte.
- Un seul clic normal : **ENREGISTRER**.
- Retour automatique au viseur après écriture dans Santé Connect.
- Galerie et saisie manuelle accessibles directement depuis le viseur.
- Suppression de l’ActionBar : le titre ne masque plus le contenu ; gestion des barres système via WindowInsets.
- Moteur photo/cadrage automatique inchangé.

## 1.5.0

Hybrid CameraX + validated still-photo workflow.

- Reintroduce a live CameraX preview.
- Analyze live frames with the same automatic locator and Omron template reader as v1.4.
- Show SYS / DIA / PULSE continuously with rolling stability indicators.
- Require a three-frame identical complete reading before automatic capture.
- Automatically capture a high-quality still JPEG once live reading is stable.
- Re-run the v1.4 still-photo pipeline on that JPEG before showing confirmation.
- Never write the live-only reading to Health Connect.
- Add a manual `Capturer et analyser maintenant` action.
- Keep photo, gallery and manual-entry fallbacks.
- Disable flash for the LCD capture path.
- Restore CAMERA permission only for direct CameraX mode.

## 1.4.0

Stable photo-first version.

- Keep the validated v1.3 automatic locator and Omron digit reader unchanged.
- Remove experimental live CameraX recognition.
- Remove manual LCD corner editing.
- Remove camera permission from the app; still-image capture is delegated to the system camera app.
- Simplify the confirmation flow.
- Disable the save button while Health Connect is being written.
- Add a clear saved state and a `Nouvelle mesure` action.