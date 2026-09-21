# Smart Copy

Copie intelligente de fichiers pour Android (APK) et Windows (.exe), gratuite.

## Téléchargement

Chaque envoi sur `main` compile automatiquement les deux versions. Elles se trouvent
dans l'onglet **Releases** du dépôt :

- `SmartCopy-N.apk` : Android 8.0 et plus
- `SmartCopy-2.0.0-portable.exe` : Windows, sans installation
- `SmartCopy-2.0.0-installation.exe` : Windows, avec installation

## Ce que fait l'application

- **Analyse des supports** : type de mémoire (SSD NVMe/SATA, disque dur, USB, carte SD,
  mémoire interne), espace libre, débit d'écriture réel mesuré pour plusieurs tailles de bloc
  (avec vidage du cache), vitesse de lecture de la source, latence des petits fichiers.
- **Réglage automatique** : taille de bloc optimale, puis ajustement en continu pendant la
  copie ; nombre de flux parallèles adapté au support.
- **Copie en pipeline** : le bloc suivant est lu pendant l'écriture du bloc courant.
- **Gros fichiers un par un** (pas de va-et-vient de la tête de lecture, moins de
  fragmentation), **petits fichiers en parallèle** (masque la latence).
- **File d'attente dynamique** : on peut ajouter des fichiers ou des dossiers pendant la
  copie, ils passent à la suite.
- **Progression détaillée** : pourcentage, octets, vitesse instantanée, moyenne, pic, temps
  restant, temps écoulé, fichiers, bloc actuel, flux actifs, échecs, graphique du débit en
  direct, progression par fichier.
- Pause, reprise, annulation (le fichier incomplet est supprimé), nouvelle tentative des
  échecs. Un fichier déjà présent n'est jamais écrasé : la copie est renommée « nom (1) ».
- Android : la copie continue écran éteint, avec la progression dans une notification.
- Windows : glisser-déposer, dates des fichiers conservées.

## Structure

```
app/          Application Android (Kotlin, Jetpack Compose)
desktop/      Application Windows (Electron)
.github/      Compilation automatique (GitHub Actions)
```
