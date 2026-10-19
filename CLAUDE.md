# Projets Telegram Android — consignes de travail (branche de base `base-ci`)

Chaque projet vit sur sa propre branche créée depuis `base-ci` ; chaque push d'une branche (sauf `master` et `base-ci`) déclenche `.github/workflows/build.yml`, qui compile un APK debug arm64 et le publie en pré-release nommée `<branche>-build-<numéro>` (page Releases du dépôt). L'APK porte le paquet `app.chronologie.messenger.beta`, le nom « Chronologie », sa propre icône et sa propre clé (`ci/chronologie.keystore`) : il s'installe par-dessus la version précédente sans déconnexion, quel que soit le projet.

- Langue de travail avec Miguel : français, en s'adressant à un non-programmeur. Code et commentaires : anglais, dans le style du fichier voisin.
- Suivre les compilations avec `gh run list` / `gh run view <id> --log-failed` ; les diagnostics sont dans le corps de la pré-release (`gh api repos/Orakleus/Telegram/releases/tags/<tag>`).
- Ne jamais modifier `BuildVars.java`, `gradle.properties`, le keystore ni le workflow sans dire pourquoi ; ne jamais committer de clé API.
- `ChatActivity.java` fait 47 000 lignes : ne jamais le lire en entier, toujours par grep et par plages de lignes.
- Nouvelles classes dans leurs propres fichiers ; dans les fichiers existants de Telegram, le minimum, à des points d'accroche nommés.
- Pas de dépendance nouvelle, pas de ressource XML, pas de changement au schéma TL.
- Zéro allocation dans `onDraw`. Toute requête réseau liée au fragment par `bindRequestToGuid`. Le serveur rationne les méthodes par compte sur plusieurs minutes : une requête à la fois, jamais de salve, `RequestFlagFailOnServerErrors` pour voir les `FLOOD_WAIT`.
- Le retour visuel vient de Miguel (captures et vidéos du téléphone) : demander précisément ce qu'il faut filmer. Pour une soumission, finir par une branche propre d'un seul commit sur `master`.
- Précédent : projet « Chronologie » (branche `chronologie`, dossier `CHRONOLOGIE.md` sur cette branche), soumis à @designers le 19 octobre 2026.
