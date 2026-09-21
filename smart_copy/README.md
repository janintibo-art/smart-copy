# Smart Copy v1.0

Application intelligente de copie de fichiers avec optimisation I/O pour Android et Windows.

## Caractéristiques

- **Benchmarking I/O en temps réel** : Analyse automatique des performances de lecture/écriture
- **Optimisation buffer dynamique** : Ajustement automatique de la taille buffer selon le débit
- **Parallélisation intelligente** : Nombre de threads adapté aux capacités du système
- **Détection stockage** : SSD vs HDD vs carte SD
- **Queue dynamique** : Ajout de fichiers en cours de copie
- **Barre de progression détaillée** : Vitesse, ETA, pourcentage, stats globales
- **Graphiques temps réel** : Visualisation du débit instantané

## Structure

```
smart_copy/
  build.gradle                   # Android build config
  FileAnalyzer.kt               # Analyse stockage (Kotlin)
  CopyEngine.kt                 # Moteur de copie (Kotlin)
  MainActivity.kt               # UI Jetpack Compose
  
  package.json                  # Electron/Node config
  main.js                       # Process principal Electron
  preload.js                    # Context isolation
  FileAnalyzer.ts              # Analyse stockage (TypeScript)
  CopyEngine.ts                # Moteur de copie (TypeScript)
  App.tsx                      # UI React
  App.css                      # Styles
```

## Installation

### Android
```bash
./gradlew build
./gradlew installDebug
```

### Windows (Electron)
```bash
npm install
npm start
```

## Utilisation

1. **Sélectionner destination** : Choisir le dossier de destination
2. **Analyser performances** : Benchmarking I/O automatique
3. **Ajouter fichiers** : Sélectionner les fichiers à copier
4. **Démarrer** : Lancer la copie optimisée

## Optimisations v1.0

- Benchmarking 10MB pour établir baseline
- Buffer auto-scaling (2-16 MB selon débit)
- Threads auto-scaling (1-8 selon CPU et I/O)
- Détection SSD/HDD
- Statistiques en temps réel
