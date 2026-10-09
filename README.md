
https://chatgpt.com/c/6a78130b-16a0-83ed-b890-dbb66ee4270e

Sur le Samsung S25FE
 Paramètres / À propos du téléphone / Informations sur le logiciel, puis taper 7 fois sur "Numéro de version" (et pas Version One UI ou Version Android)
 Paramètres / Options de développement (tout en bas) / Débogage USB (à activer)
 Paramètres / Sécurité et confidentialité / Bloqueur automatique (à desactiver)
Brancher le S25FE à l'ordi avec un câble USB DATA, et autoriser le transfert de données.

sudo apt install google-android-build-tools-36.0.0-installer google-android-cmdline-tools-19.0-installer    

cp local.properties.example local.properties
gradle wrapper --gradle-version 8.13
./gradlew clean assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk


# Algorithme de reconnaissance de l’Omron M3

L’algorithme actuel est spécialisé pour l’**Omron M3**. Il exploite fortement la géométrie fixe de l’appareil et de son écran LCD.

## 1. Localisation automatique de l’écran

On commence par appliquer **ML Kit Text Recognition**, mais uniquement aux textes imprimés du tensiomètre, pas aux chiffres LCD.

On cherche les trois repères :

```text
SYS
DIA
PULSE
```

ML Kit fournit leur position et l’orientation de leurs bounding boxes. À partir de ces trois repères, on déduit :

- l’échelle du tensiomètre dans l’image ;
- sa rotation ;
- la position approximative du rectangle LCD.

La géométrie relative du M3 étant fixe, les dimensions du LCD sont exprimées en fonction de l’espacement entre `SYS`, `DIA` et `PULSE`.

Les quatre bords estimés du LCD sont ensuite légèrement **raffinés en recherchant les gradients de luminance** à proximité. ML Kit indique donc essentiellement où chercher, et les contours du LCD ajustent le résultat.

## 2. Rectification géométrique

Les quatre coins obtenus sont transformés par une homographie vers une image canonique de :

```text
600 × 900 pixels
```

Ainsi, quelle que soit raisonnablement la distance ou la petite inclinaison de la photo, après cette étape l’écran est ramené à une géométrie fixe.

Les positions de SYS, DIA et PULSE deviennent alors constantes.

## 3. Grille connue des chiffres

Dans cette image `600 × 900`, le programme connaît à l’avance :

- la zone SYS ;
- la zone DIA ;
- la zone PULSE ;
- la taille des chiffres ;
- l’écart entre deux chiffres ;
- les trois emplacements possibles dans chaque ligne.

Il n’essaie donc **pas de segmenter librement les chiffres**.

Par exemple, pour une DIA de 75 :

```text
[ vide ][ 7 ][ 5 ]
```

et pour 136 :

```text
[ 1 ][ 3 ][ 6 ]
```

Les valeurs sont alignées à droite et les zéros initiaux sont interdits.

## 4. Suppression des reflets

Avant la reconnaissance, chaque ligne est binarisée en estimant séparément le fond local des canaux rouge, vert et bleu.

Pour chaque pixel, on calcule approximativement :

\[
d_R = B_R - R, \qquad
d_G = B_G - G, \qquad
d_B = B_B - B,
\]

puis on prend essentiellement :

\[
d = \min(d_R,d_G,d_B).
\]

Un vrai segment LCD est sombre dans **les trois canaux**, tandis qu’un reflet coloré n’est généralement sombre que dans certains d’entre eux.

Cette opération élimine notamment une grande partie des reflets rouges qui perturbaient la lecture du pouls.

Le seuil est ensuite déterminé par Otsu, avec un petit nettoyage des pixels isolés.

## 5. Modèle réel des chiffres Omron

On n’utilise plus PaddleOCR ni `ssocr`.

Chaque chiffre est décrit comme une combinaison des **sept segments à la géométrie réelle du M3** :

```text
 a
f b
 g
e c
 d
```

Une particularité importante est le `7` de l’Omron :

```text
7 Omron = a + b + c + f
```

Il possède donc bien une branche verticale supérieure gauche supplémentaire.

## 6. Comparaison par remplissage et contours

Pour chaque emplacement et chacun des sept segments, le programme mesure deux choses.

### Occupation intérieure

On mesure quelle proportion de la zone où le segment devrait être présent est effectivement noire.

### Distance aux contours

On mesure si les bords attendus du segment sont proches des contours réellement observés.

Cette seconde quantité est calculée à l’aide d’une transformée de distance de type **Chamfer**.

Pour un chiffre candidat, le coût combine approximativement :

\[
0.70 \times \text{défaut de remplissage}
+
0.30 \times \text{distance aux contours}.
\]

Une pénalité supplémentaire est appliquée lorsque des segments qui devraient être absents sont présents.

Les dix chiffres `0, …, 9` sont comparés et le chiffre de coût minimal est retenu.

## 7. Petit recalage géométrique local

Même après la rectification, quelques pixels d’erreur peuvent subsister.

Le programme essaye donc plusieurs petits déplacements communs :

\[
(dx,dy)
\]

de la grille entière.

Il ne déplace **pas indépendamment chaque chiffre** : l’écart entre les chiffres reste fixe.

Cela évite les anciennes erreurs où un chiffre pouvait être artificiellement reconnu en déplaçant trop librement sa fenêtre.

## 8. Contraintes numériques

Les solutions manifestement incompatibles avec une mesure sont éliminées par de simples garde-fous :

```text
SYS   : 20–300
DIA   : 10–300
PULSE : 1–300
```

Ces contraintes servent uniquement à la robustesse de la reconnaissance ; elles ne constituent pas une interprétation médicale.

# Mode direct CameraX

Le mode direct réutilise **exactement le même algorithme**.

CameraX fournit une image d’analyse environ toutes les **650 ms**. Chaque image passe par :

```text
SYS/DIA/PULSE
→ cadrage automatique
→ rectification
→ reconnaissance
```

Pour chacun des trois champs, on conserve les cinq dernières lectures et il faut au moins **trois votes identiques**.

En plus, le triplet complet doit être identique pendant **trois analyses consécutives**.

Par exemple :

```text
111 / 75 / 56
111 / 75 / 56
111 / 75 / 56
```

À ce moment-là seulement la lecture devient stable.

Cependant, elle n’est toujours pas enregistrée directement. CameraX prend alors une **photo haute résolution**, et cette photo repasse intégralement dans le pipeline précédent.

Le flux complet est donc :

```text
DIRECT
   ↓
sert à savoir quand la prise de vue est bonne
   ↓
PHOTO HAUTE RÉSOLUTION
   ↓
cadrage automatique
   ↓
reconnaissance définitive
   ↓
correction manuelle éventuelle
   ↓
Health Connect
```

La robustesse vient donc surtout de ce principe : **on ne fait presque plus d’OCR sur les chiffres eux-mêmes ; on exploite principalement la géométrie connue de l’Omron M3, la forme réelle de ses segments LCD et un recalage automatique de l’écran.**
