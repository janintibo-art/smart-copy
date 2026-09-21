# Smart Copy v1.0 - Contexte et Architecture

## Ce qui a été livré

**Version 1.0 complète** : moteur de copie + benchmarking I/O + UI pour APK et Windows.

### Fichiers critiques

**Moteur (identique Android + Windows) :**
- `FileAnalyzer.kt` / `FileAnalyzer.ts` : Benchmarking I/O, détection stockage
- `CopyEngine.kt` / `CopyEngine.ts` : Copie multi-threads avec optimisation buffer

**Android (Kotlin + Jetpack Compose) :**
- `MainActivity.kt` : UI avec Compose
- `build.gradle` : Configuration gradle
- `AndroidManifest.xml` : Permissions
- Sortie : `.apk` signé

**Windows (Electron + React/TypeScript) :**
- `App.tsx` / `App.css` : Interface React
- `main.js` : Process principal Electron
- `preload.js` : Context isolation
- `package.json` : Dépendances Node
- `electron-builder.json` : Build configuration
- Sorties : `.exe` portable + NSIS installer

### Architecture

1. **Benchmarking** : Test 10MB → calcule read/write speed
2. **Auto-scaling buffer** : 2-16 MB selon débit
3. **Auto-scaling threads** : 1-8 selon CPU et I/O
4. **Copie optimisée** : Streams avec buffer optimal
5. **UI temps réel** : Progress bar, speed, ETA, stats

## Comment tester

### Android (Termux)
```bash
cd ~/smart_copy
./gradlew build
adb install -r build/outputs/apk/debug/smartcopy-debug.apk
```

### Windows (Local ou cloud)
```bash
cd smart_copy
npm install
npm start
npm run build:electron
```

Génère : `smart_copy-1.0.exe` (portable) + installer NSIS

## Prochaines étapes (v1.1)

- [ ] Graphiques temps réel du débit
- [ ] Statistiques détaillées par fichier
- [ ] Pause/reprendre copie
- [ ] Estimation de temps global
- [ ] Dark/Light mode sélectable
- [ ] Historique des copies
