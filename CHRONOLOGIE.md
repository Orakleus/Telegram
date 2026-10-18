# CHRONOLOGIE — dossier de projet

Fonctionnalité pour le client Android de Telegram, à soumettre à @designers (code fonctionnel + vidéo de démonstration).
Version 1 du dossier, 18 octobre 2026.

Base de code : dépôt public `DrKLO/Telegram`, branche `master`, commit `f32ffeec` (« update to 13.0.2 (7175) », 13 octobre 2026). Toutes les lignes citées se rapportent à ce commit ; elles bougeront aux mises à jour suivantes, les noms de méthodes, eux, sont stables.

Ce fichier se dépose à la racine du dépôt cloné ; chaque session Claude Code commence par le lire (`@CHRONOLOGIE.md`).

---

## 1. En une phrase

Un défileur chronologique au bord droit du chat : on le saisit, dix ans de conversation se déploient en une règle d'années et de mois, avec la densité des échanges et les photos de chaque époque ; on relâche, on atterrit à la date.

---

## 2. L'expérience

### 2.1 Les quatre états

**Repos.** Rien n'est visible. Le chat est exactement celui d'aujourd'hui.

**Défilement.** Dès que la pastille de date flottante apparaît en haut du chat (celle qui affiche « 12 mars » pendant qu'on fait défiler), une poignée fine (6 × 36 dp, coins ronds, couleur `key_fastScrollActive`) apparaît au bord droit, à la hauteur qui correspond à la date affichée dans l'ensemble de l'historique. Elle glisse pendant le défilement et s'efface après lui, avec le même délai que la pastille.

**Saisie.** On pose le doigt sur la poignée (zone de prise de 24 dp de large sur toute la hauteur de la poignée). La règle se déploie vers la gauche en 220 ms : un panneau translucide de 56 dp au bord droit, qui porte les années en gras, les mois en traits courts, et pour chaque mois une barre horizontale dont la longueur dit le nombre de messages. La liste de messages cesse de réagir au doigt. À gauche du doigt flotte une bulle de la couleur des pastilles de service (fond `key_chat_serviceBackground`, texte `key_chat_serviceText`) qui affiche le mois et l'année sous le doigt, le nombre de messages de ce mois, et jusqu'à trois vignettes de photos de cette époque. Chaque passage d'un mois à l'autre produit un tic haptique.

**Relâchement.** La bulle se contracte vers la poignée, la règle se replie, et le chat saute à la date par la mécanique existante de « Aller à la date ». Un tap sans déplacement sur la poignée ouvre le calendrier existant.

### 2.2 La cartographie

La règle n'est pas proportionnelle au temps mais au contenu : une année qui compte dix mille messages occupe dix fois la hauteur d'une année qui en compte mille. Le haut de la règle est le premier message, le bas le dernier, comme dans le chat. Pour que chaque année reste lisible, aucune n'est plus basse que 28 dp ; les autres se partagent le reste au prorata.

Sous le doigt, la position se traduit en rang de message (« le 48 212ᵉ sur 120 000 »), puis le rang en date par interpolation entre les échantillons connus (section 3.3). Tant qu'une année n'a que son échantillon annuel, l'interpolation y est linéaire ; dès que ses mois sont échantillonnés, elle devient exacte au mois près. L'échantillonnage des mois se déclenche pour l'année sous le doigt pendant la saisie, et la bulle se corrige à mesure que les réponses arrivent.

Pendant la saisie, la liste ne bouge pas, exactement comme le défileur rapide des médias partagés : tout se joue dans la bulle, le saut ne se fait qu'au relâchement.

### 2.3 Où et quand

Chats privés, groupes, supergroupes, chaînes. Les sujets de forum sont reportés au lot 5 (leur historique se charge par une autre requête).

Jamais dans les chats secrets (aucune requête serveur possible), ni dans les modes programmés, épinglés, messages enregistrés par source, réponses rapides, ni pendant une recherche ou une sélection multiple, ni en aperçu ou en bulle, ni dans l'écran de signalement. Désactivé si le dialogue compte moins de 200 messages ou moins de 60 jours d'historique : il n'y a rien à parcourir.

Clair et sombre par le `themeDelegate` du chat (aucune couleur en dur). Droite-à-gauche : poignée et panneau passent à gauche. Tablette : identique.

### 2.4 Décisions prises par défaut

Règle proportionnelle au contenu plutôt qu'au temps ; pas de défilement de la liste pendant la saisie ; bulle au mois près ; tap simple = calendrier existant ; seuil d'activation à 200 messages et 60 jours. Chacune se renverse en une ligne si Miguel en décide autrement.

---

## 3. L'architecture, ancrée dans le code

### 3.1 Fichiers

Nouveau `TMessagesProj/src/main/java/org/telegram/ui/Components/ChatTimelineView.java` (≈ 600 lignes) : la vue, dessin Canvas, geste, animations.

Nouveau `TMessagesProj/src/main/java/org/telegram/messenger/ChatTimelineIndex.java` (≈ 350 lignes) : l'index chronologique d'un dialogue, c'est-à-dire bornes, total, échantillons, densité, positions des photos, cache.

Modifié `TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java` : une soixantaine de lignes en cinq points (3.2).

Rien d'autre. Aucune chaîne nouvelle n'est indispensable (le pluriel « messages » existe déjà : `LocaleController.formatPluralString("messages", n)`, utilisé l. 4259), aucun changement au schéma TL, aucune ressource XML. Le diff final doit rester lisible en une séance : c'est un argument de soumission.

### 3.2 Les cinq points d'accroche dans `ChatActivity`

1. **Création.** Juste après l'ajout de `floatingDateView` à `contentView` (l. 7154) : créer `timelineView = new ChatTimelineView(context, themeDelegate)`, l'ajouter à `contentView` en `Gravity.TOP | Gravity.RIGHT`, largeur 72 dp, hauteur `MATCH_PARENT`. Ses marges haute et basse suivent `chatListView.getY() + chatListViewPaddingTop` (même calcul que `floatingDateView.onDraw`, l. 7130) et le haut de `chatActivityEnterView` ; elles sont rafraîchies depuis `updateMessagesVisiblePart(boolean)` (l. 15975).

2. **Date courante.** Dans `updateMessagesVisiblePart(boolean)` (l. 15975 ; `invalidateMessagesVisiblePart()` l. 15919 ne fait que la déclencher), à l'endroit où `floatingDateView.setCustomDate(messageObject.messageOwner.date, …)` est appelé (l. 16428) : ajouter `timelineView.setCurrentDate(messageObject.messageOwner.date)`. C'est ce qui place la poignée à la bonne hauteur hors saisie.

3. **Apparition.** Dans `showFloatingDateView` (l. 13691) : `timelineView.show()`. Dans `hideFloatingDateView` (l. 13719) : `timelineView.hide(hideDateDelay)`. Le listener de défilement (`chatListView.setOnScrollListener`, l. 6819) n'a pas besoin d'être touché, ces deux méthodes en sont déjà les relais.

4. **Actions.** Le délégué de la vue appelle `jumpToDate(date)` (l. 12508) au relâchement. Pour le tap, il reproduit les lignes 1890-1897 : `CalendarActivity` en `TYPE_CHAT_ACTIVITY`, `setChatActivity(this)`, `presentFragment`.

5. **Garde et cycle de vie.** `timelineView.setAllowed(chatMode == MODE_DEFAULT && !isTopic && !isSecretChat() && !inPreviewMode && !isInsideContainer && !isReport())`. Masquage forcé quand `actionBar.isActionModeShowed()` ou que la recherche est ouverte, et du bouton de retour en bas pendant la saisie (il occupe le même coin). Dans `onFragmentDestroy()` (l. 3403) : `timelineView.destroy()`. Toute requête de l'index est liée au fragment par `getConnectionsManager().bindRequestToGuid(reqId, classGuid)` (idiome l. 4271), donc annulée avec lui.

### 3.3 L'index : trois sources, toutes déjà employées par le client

**Total et bornes.** `messages.getHistory` avec `limit = 1` renvoie un `messages.messagesSlice` (ou `channelMessages`) dont `count` est le nombre total de messages du dialogue et `offset_id_offset` le rang du message renvoyé compté depuis le plus récent. Le plus ancien message s'obtient avec `offset_date = 1, add_offset = -1, limit = 1` : c'est l'idiome déjà employé l. 4231-4235 (`gh1`) pour trouver le premier message d'une journée, appliqué à une date antérieure au dialogue. Si la réponse est un `messages.messages` sans `count` (très petit dialogue), le total vaut la taille de la liste et la fonctionnalité est de toute façon sous le seuil.

**Échantillons et densité.** Pour une date D, `getHistory(offset_date = D, limit = 1)` renvoie le dernier message avant D et son `offset_id_offset`. La différence entre deux échantillons est le nombre exact de messages entre les deux dates, pour tous les types de dialogue, chats privés à identifiants globaux compris. `ChatActivity` fait déjà précisément ce calcul l. 4242-4255 (`gh2`, différence des `offset_id_offset`) pour afficher « N messages » dans la barre de titre. L'index échantillonne d'abord chaque 1ᵉʳ janvier entre le premier message et aujourd'hui (au plus une douzaine de requêtes), puis les mois à la demande pour l'année sous le doigt (onze requêtes par année), trois requêtes en vol au maximum, résultats mis en cache par dialogue. Les rangs sont stockés depuis le plus ancien (`count - offset_id_offset`) pour rester stables quand des messages arrivent ; le cache se rafraîchit si le total a bougé de plus de 5 %.

**Vignettes.** `messages.getSearchResultsPositions` avec `inputMessagesFilterPhotoVideo`, `limit = 100` (idiome de `SharedMediaLayout`, l. 5078-5121, objets `Period` l. 1525) donne cent positions datées réparties sur tout l'historique. Leurs messages sont chargés par lots d'identifiants (`TL_channels_getMessages` pour un canal, `TL_messages_getMessages` sinon, idiome `MessagesController` l. 7876-7881), convertis en `MessageObject`, et dessinés par `ImageReceiver` à partir de `messageObject.mediaThumb` / `strippedThumb`, ou de `FileLoader.getClosestPhotoSizeWithSize(messageObject.photoThumbs, 50)` (idiome `SharedPhotoVideoCell2`, l. 371-376). Le filtre `inputMessagesFilterEmpty` n'est pas accepté par cette requête, d'où le passage par `offset_id_offset` pour la densité.

**Modèle de données.**

```
ChatTimelineIndex
  long dialogId ; int currentAccount
  int totalCount ; int firstDate ; int lastDate
  ArrayList<Sample> samples          // triés par date ; Sample { int date ; int rankFromOldest ; int msgId ; boolean monthly }
  ArrayList<Photo> photos            // Photo { int date ; int msgId ; MessageObject message (chargé à la demande) }
  cache statique LruCache<String, ChatTimelineIndex> clé "compte_dialogue", 16 entrées

  void load(callback)                // total + bornes + années (lots 2)
  void refineYear(int year)          // mois d'une année (lot 2)
  void loadPhotos()                  // positions puis messages (lot 4)
  int   rankForProgress(float p)     // 0..1 → rang, via blocs d'années (hauteur plancher 28 dp)
  int   dateForRank(int rank)        // interpolation entre échantillons
  float progressForDate(int date)    // inverse, pour placer la poignée
  int   countForMonth(int year, int month)
```

### 3.4 La vue

**Modèle.** `RecyclerListView.FastScroll` en `DATE_TYPE` (l. 520-1010) pour la poignée, la bulle et leurs animations, mais réécrit en vue autonome : la liste du chat est inversée et virtuelle (l'historique n'est pas chargé, les positions n'existent pas), ce que `FastScroll` suppose le contraire. On lui emprunte ses mesures (poignée, bulle à 240 dp du bord), ses couleurs (`key_fastScrollActive`, `key_fastScrollInactive`, `key_fastScrollText`) et sa logique d'apparition.

**Dessin.** Tout au Canvas, aucune allocation dans `onDraw` (paints, rectangles, tableaux créés une fois). `AndroidUtilities.bold()` pour les années ; `LocaleController.formatYearMont(date, true)` pour la bulle (idiome `CalendarActivity`, l. 1191). `AnimatedFloat` pour l'expansion, l'alpha de la poignée et l'échelle de la bulle ; `CubicBezierInterpolator.EASE_OUT_QUINT` pour l'ouverture, `EASE_OUT_BACK` pour l'apparition de la bulle.

**Geste.** `onTouchEvent` : `ACTION_DOWN` dans la zone de prise → `getParent().requestDisallowInterceptTouchEvent(true)`, `performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)`, expansion ; `ACTION_MOVE` → progression = (y − haut utile) / hauteur utile, bornée à [0, 1], tic `HapticFeedbackConstants.CLOCK_TICK` à chaque changement de mois ; `ACTION_UP` → saut si le déplacement dépasse le touch slop, calendrier sinon ; `ACTION_CANCEL` → repli sans saut. Hors saisie, la vue ne consomme aucun événement en dehors de la poignée : le défilement ordinaire du chat reste intact.

**Vignettes.** Trois `ImageReceiver` (coins 6 dp par `setRoundRadius`), attachés et détachés avec la vue (`onAttachedToWindow` / `onDetachedFromWindow` de `ImageReceiver`), fondu enchaîné au changement d'époque, chargés seulement pendant la saisie.

### 3.5 Budget

Au plus quinze requêtes à la première ouverture d'un dialogue, puis onze par année raffinée ; cent positions et deux requêtes de messages pour les vignettes ; aucune requête quand la vue est cachée ; zéro allocation par trame ; un `FLOOD_WAIT` est respecté (on attend le délai indiqué, aucune rafale de nouvelles tentatives). La vue est inerte, donc sans coût, tant que la poignée n'est pas visible.

### 3.6 À vérifier au lot 2, avec repli prévu

Que `offset_id_offset` est renseigné par `getHistory` dans les chats privés et les groupes de base comme dans les canaux (le code l. 4252 le suppose ; sinon, différence des identifiants de messages, exacte pour canaux et supergroupes, règle sans densité ailleurs). Que `offset_date = 1, add_offset = -1` renvoie bien le premier message (sinon `offset_id = 1, add_offset = -1`). Que les rangs restent cohérents dans un dialogue où l'utilisateur a supprimé beaucoup de messages (sinon, inexactitude tolérée : la bulle dit le mois, pas le jour).

---

## 4. Le plan par lots

Un lot = une session Claude Code, avec un critère d'acceptation qu'on peut filmer. On ne passe au lot suivant qu'une fois le critère constaté sur le téléphone.

**Lot 0 — Installation et premier build.** Section 5. Critère : l'application compilée (« Telegram Beta », paquet `org.telegram.messenger.beta`) tourne sur le téléphone à côté de l'application du Play Store, connectée à un compte.

**Lot 1 — Squelette.** `ChatTimelineView` réduite à la poignée ; intégration aux cinq points de 3.2 ; apparition et disparition synchronisées avec la pastille de date ; poignée placée par une cartographie provisoire linéaire dans le temps entre deux bornes codées en dur ; tap → calendrier existant. Critère : au défilement la poignée apparaît, bouge, disparaît ; un tap ouvre le calendrier ; rien ne change dans un chat secret.

**Lot 2 — Index et saut.** `ChatTimelineIndex` complet (total, bornes, années, mois à la demande, cache) ; vérifications de 3.6 ; règle des années et mois dessinée ; cartographie proportionnelle au contenu avec plancher ; saisie → bulle « mois année » ; relâchement → `jumpToDate`. Critère : dans un groupe de plusieurs années, on remonte de 2026 à 2018 en deux secondes, le chat atterrit dans le bon mois.

**Lot 3 — Densité, haptique, animations.** Barres de densité par mois ; compteur « N messages » dans la bulle ; tics haptiques ; animations d'ouverture, de bulle et de repli ; masquage du bouton de retour en bas pendant la saisie ; thème sombre vérifié ; droite-à-gauche vérifié. Critère : la vidéo de démonstration peut être tournée sans honte.

**Lot 4 — Vignettes.** Positions photos, chargement par lots, trois `ImageReceiver`, fondu enchaîné. Critère : en remontant la règle, les photos défilent par époque sans à-coup.

**Lot 5 — Robustesse.** Dialogues courts (seuil), dialogue vide, réponse `messages.messages` sans `count`, erreurs réseau et `FLOOD_WAIT`, rotation, tablette (`AndroidUtilities.isTablet()`), réglage « réduire les animations », sujets de forum via `messages.getReplies` (idiome `MessagesController` l. 11614) ; liste de tests manuels exécutée ; diff relu, commentaires de tête de fichier, commit propre sur une branche `chronologie`. Critère : la liste de tests passe sur deux téléphones ou un téléphone et l'émulateur.

**Lot 6 — Vidéo, description, soumission.** Sections 7 et 8.

Ordre de grandeur : une semaine pour le lot 0 (surtout de l'attente de compilation), puis deux à trois semaines de sessions pour les lots 1 à 5.

---

## 5. Fabrication : la voie « nuage » (retenue le 18 octobre 2026)

Le PC de Miguel (8 Go de mémoire, 10 Go libres) ne peut pas compiler Telegram ; rien n'y est installé. Tout se passe sur GitHub.

**Dépôt.** Copie personnelle (fork) `Orakleus/Telegram` de `DrKLO/Telegram`, branche de travail `chronologie`. Les compilations sont gratuites (dépôt public, runners GitHub standard : 4 cœurs, 16 Go).

**Chaîne de compilation.** `.github/workflows/chronologie.yml` : à chaque push sur `chronologie`, un runner libère du disque, récupère les sources et sous-modules, installe JDK 17, SDK 36, NDK 27.2.12479018 et CMake 3.22.1, injecte `api_id` / `api_hash` depuis les secrets du dépôt (`TELEGRAM_API_ID`, `TELEGRAM_API_HASH` ; sans eux, valeurs par défaut du dépôt), restreint le build à l'ABI du téléphone de test (arm64-v8a, par `-Pandroid.injected.build.abi` et l'`abiFilters` de l'application), branche ccache sur CMake, compile `:TMessagesProj_App:assembleAfatDebug` et publie l'APK en pré-release GitHub (`Telegram-Chronologie-buildN-arm64.apk`). Premier build de l'ordre d'une heure ; les suivants plus courts grâce aux caches. Signature : le `release.keystore` factice du dépôt, utilisé par la configuration `debug` (`TMessagesProj_App/build.gradle` l. 55-60), donc constante d'un build à l'autre : chaque nouvel APK s'installe par-dessus le précédent sans réinstallation ni reconnexion.

**Installation sur le téléphone (Galaxy S23+).** Ouvrir https://github.com/Orakleus/Telegram/releases, toucher le fichier `.apk` du dernier build, l'ouvrir depuis les téléchargements, autoriser l'installation depuis le navigateur si Android le demande. L'application s'appelle « Telegram Beta » (paquet `org.telegram.messenger.beta`) et cohabite avec le Telegram du Play Store. Connexion au compte une seule fois.

**Retour visuel.** Captures et enregistrements d'écran faits sur le téléphone (enregistreur intégré de One UI), envoyés dans la conversation ; aucun câble, aucun adb.

**Qui fait quoi.** Claude, depuis une session reliée au dépôt, écrit le code, pousse sur `chronologie`, suit les compilations (`gh run list`, `gh run view --log-failed`) et corrige ; Miguel installe, teste, filme, juge le geste.

**Voie locale, pour mémoire.** Sur une machine à 16 Go et 60 Go libres : Android Studio, SDK 36, NDK 27.2.12479018, CMake, `git clone --recursive --shallow-submodules`, `./gradlew :TMessagesProj_App:assembleAfatDebug`, `adb install -r TMessagesProj_App/build/outputs/apk/afat/debug/app.apk`. La variante `afatDebug` est la seule variante de debug (`TMessagesProj_App/build.gradle` l. 161-166).

---

## 6. Travailler avec Claude Code

**Règles permanentes, dans `CLAUDE.md` à la racine du dépôt :**

```
Projet : fonctionnalité « Chronologie » pour Telegram Android. Lire CHRONOLOGIE.md avant tout.
Langue de travail avec Miguel : français, en s'adressant à un non-programmeur. Code et commentaires : anglais, dans le style du fichier voisin.
Branche de travail : chronologie. Chaque push déclenche .github/workflows/chronologie.yml, qui publie l'APK en pré-release (page Releases du dépôt).
Suivre les compilations avec gh run list / gh run view <id> --log-failed ; corriger soi-même avant de solliciter Miguel.
Ne jamais modifier BuildVars.java, gradle.properties, le keystore ni le workflow sans dire pourquoi ; ne jamais committer de clé API.
ChatActivity.java fait 47 000 lignes : ne jamais le lire en entier, toujours par grep et par plages de lignes.
Nouvelles classes dans leurs propres fichiers ; dans ChatActivity, le minimum (cinq points de CHRONOLOGIE.md §3.2).
Pas de dépendance nouvelle, pas de ressource XML, pas de changement au schéma TL.
Zéro allocation dans onDraw. Toute requête réseau liée au fragment par bindRequestToGuid.
Le retour visuel vient de Miguel (captures et vidéos du téléphone) : demander précisément ce qu'il faut filmer.
```

**Déroulé d'une session, lot par lot.** Lire les sections utiles du dossier, écrire le code, pousser sur `chronologie`, attendre le build, donner à Miguel le lien de la release et la liste courte de ce qu'il doit regarder et filmer, puis corriger sur ses retours. Les vérifications du lot 2 (§3.6) se journalisent par `FileLog.d` et se lisent dans les journaux que l'application peut exporter (Paramètres → menu de debug), ou plus simplement s'affichent temporairement dans la bulle.

**Ce que Claude ne fera pas seul :** installer l'APK, se connecter au compte, juger le ressenti du geste (vitesse, tics, inertie) et décider des réglages fins. C'est la part de Miguel, et c'est la part que Telegram regarde.

---

## 7. Storyboard de la vidéo (60 à 75 secondes)

0-5 s. Un groupe ouvert, actif depuis des années, thème clair. Titre sobre en surimpression : « Timeline ».

5-15 s. Défilement rapide vers le haut : la pastille de date apparaît, la poignée aussi, elle suit.

15-35 s. Le pouce prend la poignée : la règle se déploie, années, mois, barres de densité. Remontée lente de 2026 à 2019 ; la bulle égrène les mois, les vignettes changent d'époque, les tics s'entendent si le son est gardé.

35-45 s. Relâchement : la bulle se contracte, le chat atterrit en 2019. Un message daté à l'écran, sans coupe.

45-55 s. Tap simple sur la poignée : le calendrier existant s'ouvre, pour montrer que la fonctionnalité prolonge ce qui existe.

55-65 s. Thème sombre, dans une chaîne très dense : la règle reflète une autre forme d'historique.

65-75 s. Le geste complet, d'un trait, en deux secondes. Fin.

Tournage : enregistreur d'écran, 1080p, 60 images par seconde, téléphone en portrait, aucun montage au-delà des coupes et de deux ou trois sous-titres en anglais. Pas de musique.

---

## 8. Brouillon de description pour @designers

En anglais, car l'équipe travaille en anglais. À envoyer en message direct avec la vidéo et le lien vers la branche.

> **Timeline — scrub through years of a chat in one gesture (Android)**
>
> When you scroll fast, a thin handle appears on the right edge, aligned with the date you are looking at across the whole history. Grab it and the timeline unfolds: years and months, a density bar per month, the photos of each period in a floating bubble, a haptic tick per month. Release, and the chat jumps to that date through the existing "jump to date" path. A single tap opens the existing calendar.
>
> Fully client-side, no API or schema changes. The index uses `messages.getHistory` with `offset_date` and `offset_id_offset` (the same calculation ChatActivity already does to count a day's messages), so it is exact for every chat type, and `messages.getSearchResultsPositions` for the photo thumbnails, as in shared media. At most ~15 requests on first open per chat, cached afterwards; zero allocations in onDraw; the view costs nothing while hidden.
>
> Two new files (`ChatTimelineView`, `ChatTimelineIndex`) and about sixty lines in `ChatActivity` at five anchor points. Light and dark themes, RTL, tablets. Not enabled in secret chats, scheduled/pinned/saved modes, search, or chats under 200 messages.
>
> Branch: <lien>. Video: attached. Happy to adjust mapping (content-proportional vs. time-proportional), thresholds, or visuals.

---

## 9. Risques et réponses

**Charge serveur.** Une douzaine de requêtes légères à la première ouverture, puis cache ; Telegram pourra préférer une requête serveur dédiée, le code l'accueillera en remplaçant une seule méthode de l'index.

**Exactitude.** Les rangs sont exacts à la suppression près ; la bulle annonce le mois, jamais le jour, et le saut final passe par leur propre `jumpToDate`, qui charge autour de la date demandée.

**Intégration dans `ChatActivity`.** Le fichier est énorme et mouvant ; on n'y touche qu'aux cinq points, et chaque mise à jour de Telegram pendant le projet se rebase en quelques minutes.

**Chevauchement avec le bouton de retour en bas.** Masqué pendant la saisie ; la poignée seule est assez fine pour cohabiter.

**Acceptation.** Telegram n'a jamais proposé de navigation continue dans l'historique, seulement le calendrier ; le geste est dans leur langage (défileur des médias partagés, pastille de date), c'est l'argument central. Le risque est qu'ils préfèrent une règle proportionnelle au temps : la cartographie est isolée dans deux méthodes de l'index, l'alternative se livre en une heure.

---

## Sources du dossier

- Dépôt DrKLO/Telegram, commit f32ffeec (13.0.2) : `ChatActivity.java` l. 1886-1898, 4231-4271, 6819, 7097-7168, 12508-12559, 13691-13745, 15975, 16428 ; `RecyclerListView.java` l. 292-320, 520-1010 ; `SharedMediaLayout.java` l. 1525, 5078-5135, 8282-8335, 8725-8764 ; `CalendarActivity.java` l. 461-521, 1191 ; `SharedPhotoVideoCell2.java` l. 371-376 ; `MessagesController.java` l. 7876-7881, 11614 ; `BuildVars.java` l. 29-30 ; `TMessagesProj_App/build.gradle` l. 71-100, 120-166 ; `gradle.properties` l. 23 ; `README.md`.
- Annonce « Designers × AI » du canal @designers (message 266).
- Documentation Claude Code : https://code.claude.com/docs/en/setup.

---

## Journal

**18 octobre 2026.** Dossier, chaîne de compilation et lots 1-2 écrits et poussés le même jour. Enseignements de la mise en route : (1) l'option `-Pandroid.injected.build.abi` range l'APK sous `build/intermediates/apk/` et le marque `testOnly`, que l'installateur du téléphone refuse ; d'où `-Pandroid.injected.testOnly=false`. (2) Play Protect bloque comme « dangereuse » une application qui porte le nom de paquet de Telegram sans sa signature, et le keystore factice public du dépôt est connu : le build de test a donc son propre paquet (`app.chronologie.messenger.beta`), son nom (« Chronologie »), son icône et sa clé (`ci/chronologie.keystore`). (3) Sur Galaxy S23+, le Bloqueur automatique Samsung doit être désactivé le temps de l'installation. (4) L'écran de connexion d'une version de développement propose une case « Test Backend » : cochée, on atterrit sur les serveurs de test, compte vide ; la décocher avant de saisir le numéro. Build 8 installé et validé sur le téléphone : poignée, règle des années proportionnelle, bulle, saut. Retour de Miguel : le disque est un peu bas au repos ; lot 3 réserve 64 dp au-dessus du composeur et écarte les boutons latéraux pendant la saisie.
