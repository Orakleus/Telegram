# Chronologie — consignes de travail

Projet : fonctionnalité « Chronologie » pour Telegram Android (défileur chronologique du chat), à soumettre à @designers. Lire CHRONOLOGIE.md avant tout.

- Langue de travail avec Miguel : français, en s'adressant à un non-programmeur. Code et commentaires : anglais, dans le style du fichier voisin.
- Branche de travail : `chronologie`. Chaque push déclenche `.github/workflows/chronologie.yml`, qui compile un APK debug arm64 et le publie en pré-release (page Releases du dépôt).
- Suivre les compilations avec `gh run list` / `gh run view <id> --log-failed` ; corriger soi-même avant de solliciter Miguel.
- Ne jamais modifier `BuildVars.java`, `gradle.properties`, le keystore ni le workflow sans dire pourquoi ; ne jamais committer de clé API (elles vivent dans les secrets du dépôt).
- `ChatActivity.java` fait 47 000 lignes : ne jamais le lire en entier, toujours par grep et par plages de lignes.
- Nouvelles classes dans leurs propres fichiers ; dans `ChatActivity`, le minimum (cinq points de CHRONOLOGIE.md §3.2).
- Pas de dépendance nouvelle, pas de ressource XML, pas de changement au schéma TL.
- Zéro allocation dans `onDraw`. Toute requête réseau liée au fragment par `bindRequestToGuid`.
- Le retour visuel vient de Miguel (captures et vidéos du téléphone) : demander précisément ce qu'il faut filmer.
