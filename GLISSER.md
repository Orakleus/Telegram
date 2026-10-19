# GLISSER — dossier de projet

Fonctionnalité pour le client Android de Telegram, à soumettre à @designers (code fonctionnel + vidéo de démonstration).
Version 1 du dossier, 19 octobre 2026. Second projet après « Chronologie » (soumis le 19 octobre 2026).

Base de code : branche `base-ci` du fork `Orakleus/Telegram` (commit `25ab817`), c'est-à-dire `DrKLO/Telegram` au commit `f32ffeec` (« update to 13.0.2 (7175) », 13 octobre 2026) plus la chaîne de compilation. Toutes les lignes citées se rapportent à ce commit ; elles bougeront aux mises à jour suivantes, les noms de méthodes, eux, sont stables.

Ce fichier se dépose à la racine du dépôt ; chaque session commence par le lire.

---

## 1. En une phrase

On tient une bulle, on la tire de côté : elle se détache et suit le doigt, une étagère des conversations récentes monte du bas de l'écran ; on la dépose sur un visage, le message est transféré.

---

## 2. L'expérience

### 2.1 Les quatre états

**Repos.** Rien n'est visible. Le chat est exactement celui d'aujourd'hui.

**Prise.** Appui long sur un message : le menu contextuel habituel apparaît (réactions, Répondre, Transférer…). Si, sans lever le doigt, on tire la bulle de côté — vers la gauche ou vers la droite, d'au moins 0,4 cm, déplacement horizontal dominant — le menu se referme, un tic haptique marque le décrochage, et la bulle se détache : une copie de la bulle (avec sa queue, sans la date ni les coches) rétrécit à 60 % autour du doigt en 180 ms, prend une ombre, et suit le doigt en gardant le point de prise. Le chat derrière reste assombri (le même voile de 20 % que celui du menu). Le reste du chat ne réagit plus au doigt. Dans le même temps l'étagère monte du bas de l'écran en 260 ms : un panneau à coins arrondis (18 dp), fond `key_dialogBackground`, poignée `key_sheet_scrollUp`, qui porte deux rangées de quatre conversations — les mêmes cellules que la feuille « Transférer » (avatar de 56 dp, prénom en dessous) : « Messages enregistrés » en premier, puis les sept conversations les plus récentes où l'on peut écrire.

**Survol.** Quand le doigt entre dans l'étagère, la copie de la bulle se réduit encore, en 160 ms, à une vignette d'au plus 120 × 80 dp posée au-dessus du doigt (bord inférieur 24 dp plus haut que lui) : le visage sous le doigt reste visible. La cellule survolée se coche (la coche bleue animée de la feuille « Transférer ») et grossit de 8 % ; un tic haptique marque chaque changement de cellule ; quitter la cellule la décoche.

**Dépôt.** On relâche sur une cellule : la vignette file dans l'avatar en 220 ms (échelle vers 0,1, fondu), l'étagère redescend, le voile se lève, et le bandeau « Message forwarded to Maman » apparaît avec Annuler — exactement le bandeau du Quick Share des chaînes : l'envoi est différé le temps du bandeau et ne part pas si l'on touche Annuler (vers « Messages enregistrés » l'envoi est immédiat, comme chez eux). On relâche ailleurs, ou le geste est interrompu (appel, rotation) : la copie revient élastiquement à sa place d'origine en 260 ms, l'étagère redescend, rien n'est envoyé.

### 2.2 La pile

En mode sélection (plusieurs messages cochés), le même geste — appui long sur l'un d'eux, puis tirer de côté — soulève la sélection entière : les trois bulles du dessus en éventail décalé de 6 dp, une pastille avec le nombre. Le dépôt transfère tous les messages sélectionnés, puis quitte le mode sélection. Le message sous le doigt fait toujours partie de la pile, même si l'appui long vient de le décocher.

### 2.3 Où et quand

Chats privés, groupes, supergroupes, chaînes, fils de discussion et sujets de forum (côté départ). À l'arrivée : toute conversation où l'on peut écrire, sauf les forums et monoforums (qui exigeraient le choix d'un sujet), les conversations payantes en étoiles et celles qui refusent le message pour cause de Premium ; elles passent par « Transférer » comme avant.

Jamais dans les chats secrets (`currentEncryptedChat != null`), ni dans les modes programmés, épinglés, enregistrés, réponses rapides, suggestions, ni en aperçu, en bulle, ni dans l'écran de signalement ; jamais pour un message que « Transférer » refuse déjà (`MessageObject.canForwardMessage()` faux, contenu protégé `isPeerNoForwards()`, message en cours d'envoi ou en échec, message sponsorisé). Dans ces cas l'appui long garde strictement son comportement actuel.

Clair et sombre par le `themeDelegate` du chat. Droite-à-gauche : l'étagère est symétrique, rien à faire. Tablette : identique.

### 2.4 Décisions prises par défaut

Déclenchement par appui long puis tirer de côté (le tirer vertical après appui long reste leur sélection multiple) ; huit cibles sans défilement ; vignette au-dessus du doigt dans l'étagère ; envoi différé avec Annuler comme Quick Share ; forums exclus à l'arrivée. Chacune se renverse en une ligne si Miguel en décide autrement.

### 2.5 Ce qui existe déjà chez eux, et ce que Glisser y ajoute

Telegram 13.0 a « Quick Share » : sur un post de chaîne, un appui long sur la flèche de partage fait surgir une bulle de cinq visages (`QuickShareSelectorOverlayLayout`, `QuickShareSelectorDrawable`) ; on glisse jusqu'à l'un d'eux et on relâche. Glisser en est le prolongement : il fonctionne sur tout message de toute conversation (les chats privés et les groupes n'ont pas de flèche de partage), la bulle elle-même est la poignée, les cibles incluent groupes et chaînes et commencent par Messages enregistrés, une sélection se déplace en pile — et le transfert emprunte exactement leur chemin d'envoi et leur bandeau avec Annuler. C'est l'argument de la soumission : « the Quick Share gesture, extended to every message through the bubble itself ».

---

## 3. L'architecture, ancrée dans le code

### 3.1 Fichiers

Nouveau `TMessagesProj/src/main/java/org/telegram/ui/Components/MessageDragController.java` (≈ 450 lignes) : la machine à états du geste, le calque de dessin (voile, bulle soulevée, vignette, pile), les animations, l'interface déléguée vers `ChatActivity`.

Nouveau `TMessagesProj/src/main/java/org/telegram/ui/Components/DropShelfView.java` (≈ 250 lignes) : l'étagère — fond, poignée, grille de `ShareDialogCell`, choix des conversations, survol, montée et descente, centre de l'avatar visé.

Modifié `TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java` : une quarantaine de lignes en cinq points (3.2).

Rien d'autre. Aucune chaîne nouvelle (le bandeau est celui de `BulletinFactory.createForwardedBulletin`), aucune ressource XML, aucun changement au schéma TL, aucune requête réseau propre : le seul appel serveur est le transfert lui-même.

### 3.2 Les cinq points d'accroche dans `ChatActivity`

1. **Création.** À la fin de `createView` (juste avant `onBottomItemsVisibilityChanged()`, l. 9021) : `messageDrag = new MessageDragController(this, contentView, chatListView, themeDelegate, delegate)` ; le contrôleur ajoute lui-même son calque et l'étagère à `contentView`, en dernier, donc au-dessus de tout (le composeur, les boutons latéraux). Le calque est en `Gravity.TOP`, `MATCH_PARENT`, placé sous la barre d'action par `onLayout` (l. 18681).

2. **Événements.** Première ligne de `ChatActivityFragmentView.dispatchTouchEvent` (l. 17527) : `if (messageDrag != null && messageDrag.onDispatchTouchEvent(ev)) return true;`. Il faut être avant la ligne 17538, qui laisse tomber tous les événements tant que le menu contextuel est ouvert (`scrimView != null`) : c'est précisément pendant cette période que le doigt, toujours posé, annonce le tirer. Le flux tactile reste adressé à la fenêtre principale pendant toute la durée du geste, le menu étant une autre fenêtre ; pendant le tirer, le contrôleur consomme tout et aucun enfant ne reçoit plus rien, ce qui neutralise le balayage-réponse (`processTouchEvent`, l. 5031) et la sélection multiple de la liste (`RecyclerListView.startMultiselect`, l. 2905, à laquelle on envoie un `ACTION_CANCEL` au décrochage). Le retour arrière par balayage de `ActionBarLayout` (l. 1411-1450) ne peut pas démarrer : `requestDisallowInterceptTouchEvent` émis par la liste à l'appui long l'a déjà désarmé (l. 1045).

3. **Armement.** Dans `createMenu`, juste après `dimBehindView(v, true)` (l. 32550) : `if (messageDrag != null) messageDrag.armFromMenu(v)` — la cellule pressée et la position du doigt viennent de là. En mode sélection, l'appui long ne passe pas par `createMenu` mais par `processRowSelect` (branche `else` du `onItemLongClickListener`, l. 1725-1732) : armement là aussi, avec le drapeau « pile ». Au décrochage le contrôleur appelle `closeMenu()` (l. 32812) : le menu se replie, son voile s'estompe en 150 ms tandis que celui du calque s'installe.

4. **Transfert.** Le délégué, implémenté dans `ChatActivity` à côté de `sendMessageFromQuickShare` (l. 39358), reprend ce code à l'identique : `AlertsCreator.checkSlowMode`, album complet via `groupedMessagesMap` (l. 39374-39384), `BulletinFactory.createForwardedBulletin(..., Bulletin.DURATION_PROLONG, null, envoiDifféré)` (l. 39396) où l'envoi différé est `SendMessagesHelper.sendMessage(messages, did, false, false, true, 0, 0)` (signature l. 2216 de `SendMessagesHelper`) suivi de `AlertsCreator.showSendMediaAlert` (l. 6750 d'`AlertsCreator`) ; envoi immédiat vers Messages enregistrés. Pour la pile : les messages de `selectedMessagesIds` dans l'ordre du chat, puis `clearSelectionMode()`.

5. **Garde et cycle de vie.** `messageDrag.setAllowed(chatMode == MODE_DEFAULT && currentEncryptedChat == null && !inPreviewMode && !isInsideContainer && !isReport())`, posé à la création (ces drapeaux ne changent pas pendant la vie du fragment) ; le filtre par message (2.3) est fait par le délégué au moment de l'armement. `canBeginSlide()` (l. 36902) et `isSwipeBackEnabled()` (l. 36914) rendent faux pendant le geste, comme pour `quickShareSelectorOverlay`. Dans `onFragmentDestroy()` (l. 3403) : `messageDrag.destroy()`.

### 3.3 Le contrôleur

**États.** `IDLE` → `ARMED` (menu ouvert, cellule connue, doigt posé) → `DRAGGING` → `DROPPING` ou `RETURNING` → `IDLE`. Le passage `ARMED → DRAGGING` exige un déplacement horizontal ≥ `AndroidUtilities.getPixelsInCM(0.4f, true)` avec `|dx| / 3 > |dy|` (le seuil même du balayage-réponse, l. 5064, et du retour arrière, l. 1438) ; un `ACTION_UP` en `ARMED` ne fait rien (c'est un appui long ordinaire, le menu reste). Tout `ACTION_CANCEL` en `DRAGGING` déclenche `RETURNING`.

**La copie de la bulle.** Au décrochage, une seule allocation : un `Bitmap` aux dimensions du fond de bulle (`cell.getBackgroundDrawableLeft/Right/Top/Bottom()`, l. 21824-21890, plus 8 dp de marge) dans lequel la cellule se dessine (`cell.draw(canvas)` translaté, `drawingToBitmap = true` pendant l'opération, l. 15219). Pour la pile : un bitmap par bulle visible, trois au plus. Le dessin par trame ne fait que `drawBitmap` avec un `Paint` partagé, dans une matrice calculée depuis trois `AnimatedFloat` : échelle (1 → 0,6 → vignette), position (suivi du doigt, puis vol vers l'avatar ou retour à l'origine), alpha. L'ombre est celle d'une élévation de 12 dp sur le calque (`setOutlineProvider` en rectangle arrondi), sans filtre de flou. `CubicBezierInterpolator.EASE_OUT_QUINT` pour le décrochage et la montée, `EASE_OUT_BACK` pour le retour, `EASE_IN` pour le vol dans l'avatar. Zéro allocation dans `onDraw`.

**Le voile.** Dessiné par le calque lui-même : noir à 20 %, comme `scrimPaint` (l. 17901), fondu en 150 ms à l'entrée et à la sortie. Pendant le geste les boutons latéraux (« descendre », mentions, réactions) sont masqués avec le voile (comme le fait `scrimView`, l. 17665).

**Interactions avec la liste.** Pendant `DRAGGING` : `chatLayoutManager.setCanScrollVertically(false)` est déjà posé par `createMenu` (l. 32548) et rétabli par la fermeture du menu ; le contrôleur ne touche pas à la liste, il la prive d'événements. La cellule d'origine reste dessinée à sa place, sous le voile.

### 3.4 L'étagère

`DropShelfView extends FrameLayout` : un fond arrondi en haut (18 dp, `key_dialogBackground`, ombre d'élévation 8 dp), une poignée (36 × 4 dp, `key_sheet_scrollUp`), une `RecyclerListView` en `GridLayoutManager(4)` de `ShareDialogCell(TYPE_SHARE)` (cellule de 103 dp de haut, l. 198 de `ShareDialogCell`, avatar de 56 dp centré à 35 dp du haut, l. 109-205), défilement désactivé, hauteur 12 + 2 × 103 dp plus l'inset bas. Montée : `translationY` de la hauteur à 0 en 260 ms ; descente symétrique. `dialogAt(x, y)` rend la conversation sous un point du calque (ou 0) ; `setHovered(did)` coche, décoche et met à l'échelle ; `avatarCenter(did, out)` donne la cible du vol.

**Les conversations.** L'ordre de la feuille « Transférer » (`ShareAlert.ShareDialogsAdapter.fetchDialogs`, l. 2911-2965) : `dialogsForward.get(0)` (Messages enregistrés) puis `getAllDialogs()` dans l'ordre, en gardant les mêmes exclusions (chats secrets, chaînes où l'on ne peut pas poster, gigagroupes sans droits, chats quittés, archivés après), en excluant de plus forums et monoforums (`chat.forum`, `chat.monoforum`), les utilisateurs supprimés, les conversations payantes (`getSendPaidMessagesStars(did) > 0`) et bloquées Premium (`isUserContactBlocked`), et en s'arrêtant à huit. Reconstruit à chaque décrochage : l'ordre des conversations récentes est toujours celui du moment.

### 3.5 Budget

Aucune requête réseau hors le transfert ; une allocation de bitmap par bulle soulevée, libérée en fin de geste ; une seule étagère et un seul calque par `ChatActivity`, créés à la construction, invisibles (`GONE`) au repos, donc sans coût de dessin ni de mesure tant qu'on ne tire rien.

### 3.6 À vérifier au lot 1, avec repli prévu

Que la fenêtre principale reçoit bien les `ACTION_MOVE` du doigt resté posé pendant que le menu contextuel (une autre fenêtre, `scrimPopupWindow`, l. 32435) est affiché : c'est le comportement normal d'Android (le flux reste adressé à la fenêtre qui a reçu l'`ACTION_DOWN`), mais c'est la pierre angulaire du geste. Si les événements n'arrivaient pas, repli : armer le geste au moment de l'appui long sans ouvrir le menu tant que le doigt n'a pas bougé de 0,4 cm ou n'est pas resté 300 ms de plus (le menu s'ouvrirait alors avec ce retard), ou, moins élégant, un élément « Glisser » dans le menu. Que la copie dessinée par `cell.draw` ne déclenche pas d'animations ou d'effets parasites (spoilers, emoji animés) : sinon passer par `drawingToBitmap` et les paramètres de transition de la cellule. Que `ShareDialogCell` accepte d'être cochée et mise à l'échelle sans adaptateur de sélection (sinon la grille porte sa propre coche).

---

## 4. Le plan par lots

Un lot = une session, avec un critère d'acceptation qu'on peut filmer. On ne passe au lot suivant qu'une fois le critère constaté sur le téléphone.

**Lot 0 — Branche et dossier.** Branche `glisser` créée depuis `base-ci`, ce dossier et la ligne de projet dans `CLAUDE.md`. Pas de build (les `.md` ne déclenchent pas le workflow).

**Lot 1 — Le geste de bout en bout.** Contrôleur et étagère ; les cinq points de 3.2 ; appui long, tirer de côté, bulle soulevée qui suit le doigt, étagère à huit, survol coché, dépôt → bandeau « forwarded » avec Annuler, retour élastique si l'on lâche ailleurs. Critère : dans un chat privé, un message tiré sur « Messages enregistrés » y arrive ; tiré sur un contact, le bandeau apparaît, Annuler l'arrête ; lâché au milieu de l'écran, la bulle revient.

**Lot 2 — Finition.** Vol de la vignette dans l'avatar ; rétrécissement en vignette au-dessus du doigt dans l'étagère ; ombre, voile, haptique au décrochage et à chaque cellule ; masquage des boutons latéraux ; albums (copie de la bulle pressée, badge avec le nombre de médias) ; thème sombre et clavier ouvert vérifiés. Critère : la vidéo de démonstration peut être tournée sans honte.

**Lot 3 — La pile et les cas limites.** Sélection multiple en pile (2.2) ; messages non transférables (l'appui long ne change pas) ; mode lent, conversations payantes, forums exclus ; rotation et appel pendant le geste → retour sans envoi ; tablette ; réglage « réduire les animations ». Critère : la liste de tests manuels passe.

**Lot 4 — Relecture.** Diff relu comme un relecteur de Telegram, resserré, commentaires de tête de fichier en anglais, cartouche de diagnostic retiré s'il y en a eu un, commit unique sur `master` dans une branche `glisser-submission`.

**Lot 5 — Vidéo, description, soumission.** Sections 6 et 7.

Ordre de grandeur : deux ou trois jours de sessions.

---

## 5. Fabrication

La voie « nuage » de Chronologie, inchangée : chaque push sur `glisser` déclenche `.github/workflows/build.yml`, qui publie l'APK en pré-release `glisser-build-<n>` sur https://github.com/Orakleus/Telegram/releases. L'application s'appelle toujours « Chronologie » (paquet `app.chronologie.messenger.beta`, même clé) : elle s'installe par-dessus la build 22 sans déconnexion ; Bloqueur automatique désactivé le temps de l'installation. Le premier build de la branche prend de l'ordre d'une heure (le cache natif est propre à chaque branche), les suivants moins.

**Qui fait quoi.** Claude écrit le code, pousse, suit les builds, corrige ; Miguel installe, teste avec son compte, filme, juge le geste. Ce que Claude ne fera pas seul : juger le seuil de décrochage, la taille de la copie, la vitesse des animations, la lisibilité de l'étagère. C'est la part de Miguel, et c'est la part que Telegram regarde.

---

## 6. Storyboard de la vidéo (45 à 60 secondes)

0-4 s. Un chat privé, thème clair. Titre sobre en surimpression : « Drag to forward ».

4-14 s. Appui long sur une photo : le menu apparaît ; le pouce tire de côté : le menu se referme, la bulle se détache et suit, l'étagère monte.

14-24 s. Le pouce passe sur deux visages (coche, tic), s'arrête sur un contact, relâche : la vignette file dans l'avatar, bandeau « Message forwarded to … » avec Annuler.

24-32 s. Même geste sur un message texte, lâché au milieu de l'écran : retour élastique, rien n'est envoyé.

32-44 s. Un groupe, trois messages sélectionnés : la pile se soulève, dépôt sur Messages enregistrés, bandeau.

44-54 s. Thème sombre, une chaîne : le geste complet en trois secondes.

54-60 s. Carton de fin.

Tournage : enregistreur d'écran, 1080p, 60 images par seconde, portrait, aucun montage au-delà des coupes et de deux ou trois sous-titres en anglais. Pas de musique.

---

## 7. Description pour @designers (brouillon)

> **Drag to forward — pull any message onto a chat (Android)**
>
> Long-press a message, then pull it sideways: the bubble detaches and follows your finger while a shelf of your recent chats rises from the bottom — Saved Messages first. Hover a face, it ticks; drop, and the message is forwarded through the same path and the same undo toast as Quick Share. Release anywhere else and the bubble springs back. In selection mode, the whole selection travels as a stack.
>
> It is Quick Share extended to every message of every chat, through the bubble itself: private chats and groups have no share arrow, this gesture needs none. Working build, ready to integrate: two new classes (`MessageDragController`, `DropShelfView`), about forty lines in `ChatActivity` at five anchor points, `ShareDialogCell` and `createForwardedBulletin` reused as they are. No new API, no schema change, no new resources; one bitmap per lifted bubble, zero allocations per frame; light and dark themes, RTL, tablets. Not shown in secret chats, in scheduled/pinned/saved modes, or for messages that cannot be forwarded.
>
> Branch, one commit on top of master: https://github.com/Orakleus/Telegram/compare/master...glisser-submission
> Video: attached.
>
> Happy to adjust the trigger, the shelf (five faces like Quick Share, or a scrolling grid), or the visuals.

---

## 8. Risques et réponses

**« Nous avons Quick Share. »** C'est l'argument autant que le risque : Glisser généralise leur geste, réutilise leur chemin d'envoi et leur bandeau, et son étagère peut devenir leur bulle de cinq visages en changeant une classe.

**Transferts accidentels.** Trois verrous : l'appui long, le tirer latéral d'au moins 0,4 cm, le dépôt explicite sur un visage ; et l'envoi différé avec Annuler.

**Événements perdus sous le menu.** Section 3.6, repli prévu.

**Intégration dans `ChatActivity`.** Cinq points, une quarantaine de lignes ; chaque mise à jour de Telegram pendant le projet se rebase en quelques minutes.

---

## Sources du dossier

- Dépôt Orakleus/Telegram, branche base-ci (25ab817 = DrKLO f32ffeec) : `ChatActivity.java` l. 1708-1760, 1764-1866, 3403, 5031-5090, 9019-9026, 17527-17560, 17665, 17880-17960, 18255-18275, 18681, 30752-30800, 32435-32560, 32812-32838, 36902-36925, 39328-39404, 47153 ; `RecyclerListView.java` l. 270-278, 1270-1320, 2543-2555, 2905-2950 ; `ActionBarLayout.java` l. 1040-1048, 1411-1470, 3351-3371 ; `ShareAlert.java` l. 2401-2520, 2895-3050 ; `ShareDialogCell.java` l. 89-300 ; `QuickShareSelectorOverlayLayout.java` (227 l.) ; `QuickShareSelectorDrawable.java` l. 1-140 ; `BulletinFactory.java` l. 1374-1390 ; `SendMessagesHelper.java` l. 2216-2235 ; `AlertsCreator.java` l. 783, 6750 ; `MessageObject.java` l. 10188-10210, 11644-11648 ; `ChatMessageCell.java` l. 15219, 21824-21890.
- Annonce « Designers × AI » du canal @designers (message 266).
- Dossier CHRONOLOGIE.md (branche `chronologie`) pour la méthode et les enseignements de fabrication.

---

## Journal

**19 octobre 2026, soir.** Choix de « Glisser » parmi trois propositions (Glisser, Écouter, Fil). Repérage dans le code ; découverte du Quick Share de 13.0 (bulle de cinq visages sur la flèche de partage des posts de chaîne), qui devient l'argument de la soumission plutôt qu'un obstacle. Dossier et branche `glisser` créés.
