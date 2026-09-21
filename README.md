# Smart Copy

Copie intelligente de fichiers pour Android (APK) et Windows (.exe), gratuite.

## Téléchargement

Chaque envoi sur `main` compile automatiquement les deux versions. Elles se trouvent
dans l'onglet **Releases** du dépôt :

- `SmartCopy-N.apk` : Android 8.0 et plus
- `SmartCopy-5.0.0-portable.exe` : Windows, sans installation
- `SmartCopy-5.0.0-installation.exe` : Windows, avec installation

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
- **Vérification d'intégrité** (option) : empreinte SHA-256 calculée pendant la copie, puis
  relecture de la copie et comparaison. Une copie corrompue est signalée en échec et supprimée.
- **Fichier déjà présent** (au choix) : renommer la copie « nom (1) », remplacer, ignorer, ou
  « si différent » (les fichiers identiques, comparés par empreinte, sont ignorés ; les autres
  remplacés). Sur Windows, le remplacement n'a lieu qu'une fois la nouvelle copie terminée et
  vérifiée : l'original n'est jamais abîmé par une copie ratée.
- **Mode Déplacer** : chaque original est supprimé seulement après une copie vérifiée
  (vérification toujours active dans ce mode). Sur le même support, le déplacement est
  instantané (simple changement de dossier, aucune donnée recopiée). Les dossiers vidés à la
  source sont supprimés et la structure est recréée à la destination, dossiers vides compris.
  Confirmation avant de lancer, refus de déplacer un dossier dans lui-même.
- **Écriture sûre** : chaque fichier est d'abord écrit sous un nom temporaire
  (`nom.smartcopy-N.part`) puis renommé une fois complet et vérifié. Un fichier incomplet ne
  porte jamais le vrai nom, et « Remplacer » ne détruit jamais l'original si la copie échoue.
- **Reprise après interruption** (appli fermée, plantage, téléphone éteint, câble débranché) :
  la file est sauvegardée en continu ; au lancement suivant, un bandeau propose de reprendre.
  Les restes des fichiers interrompus sont supprimés, et les fichiers déjà terminés sont
  reconnus par empreinte (pas de doublon).
- **Historique** des 50 derniers transferts : date, mode, fichiers, volume, durée, débit moyen
  et pic, destination, et liste des échecs.
- Pause, reprise, annulation (le fichier incomplet est supprimé), nouvelle tentative des échecs.
- Android : la copie continue écran éteint, avec la progression dans une notification.
- Android : **mini-fenêtre flottante** (image dans l'image). Pendant un transfert, quitter
  l'appli la réduit automatiquement en petite fenêtre par-dessus les autres applis
  (pourcentage, vitesse, temps restant, fichiers, graphique du débit) ; bouton
  « Mini-fenêtre » pour la réduire à la demande.
- Windows : glisser-déposer, dates des fichiers conservées.

## Structure

```
app/          Application Android (Kotlin, Jetpack Compose)
desktop/      Application Windows (Electron)
.github/      Compilation automatique (GitHub Actions)
```
