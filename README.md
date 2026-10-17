# LoupGarouMC — Loup-Garou pour Paper 26.3

Mini-jeu Loup-Garou inspiré de Thiercelieux : cartes de rôle animées au centre de l'écran,
menus de vote avec fond custom, nuits/jours narrés, chat de la meute, morts en spectateur.

## Installation

1. **Plugin** : pousse le dossier sur GitHub → l'onglet *Actions* compile `LoupGarouMC-1.0.0.jar`
   (Java 25, Gradle 9.1). Dépose le jar dans `plugins/` et redémarre.
2. **Resource pack** : `dist/LoupGarouMC-pack.zip` (déjà généré). Héberge-le avec un lien direct
   (ton NAS, GitHub Releases…) puis dans `plugins/LoupGarouMC/config.yml` :
   ```yaml
   resource-pack:
     url: "https://…/LoupGarouMC-pack.zip"
     sha1: ""          # vide = calculé automatiquement
   ```
   Le pack est proposé à chaque `/lg join`. Tu peux aussi le fusionner dans ton pack serveur
   (tout est sous le namespace `lgt`, aucun fichier vanilla n'est écrasé).
3. En jeu (op) : `/lg setlobby` dans le hub (là où la tête de loup est donnée), puis crée tes arènes.

## Créer une arène

1. `/lg arena create village` (ou `/lg arena create village Le Vieux Village` pour un nom affiché).
   Tu passes directement en **mode édition** : ton inventaire est mis de côté et tu reçois les outils.
2. **Armor stand** — clic droit : ajoute un spawn à tes pieds, **orienté là où tu regardes**
   (regarde vers le centre de la table). Clic gauche près d'un spawn : le retire. 16 spawns max = 16 joueurs max.
3. **Boussole** — clic droit : point d'attente (où vont les joueurs en rejoignant l'arène).
4. **Œil de l'Ender** — te téléporte de spawn en spawn pour vérifier la vue de chaque joueur.
5. **Livre** — composition des rôles de cette arène.
6. **Barrière** — terminer : tout est enregistré dans `arenas.yml` et ton inventaire revient.

Pendant l'édition, des flammes marquent chaque spawn, une flèche de particules montre son orientation
et un texte « Spawn #n » flotte au-dessus (visibles par toi seul).

Autres commandes : `/lg arena edit <id>`, `/lg arena list`, `/lg arena info <id>`,
`/lg arena rename <id> <nom>`, `/lg arena delete <id> confirm`.

## Rejoindre

Clic droit sur la tête de loup → tableau 3D **Choix de l'arène** (3 arènes par page) : bannière loup / canard,
nom de l'arène, têtes des joueurs inscrits, compteur `x/16 JOUEURS` (ou le départ, ou les survivants) et un bouton :
**JOUER** (rejoindre), **QUITTER** (tu es déjà dans la file), **EN COURS** (regarder en spectateur),
**COMPLET** ou **BIENTÔT** (arène sans spawn). Les **flèches en bas à gauche / droite** changent de page.
Vise avec le viseur et clique ; accroupis-toi pour fermer. Le tableau se met à jour chaque seconde.
Dès qu'une arène atteint `min-players`, la partie se lance toute seule après `timers.lobby` secondes
(10 s si l'arène est pleine). `auto-start: false` pour garder le lancement manuel.

## Le Maire

Le premier matin, avant le débat, le village **élit un Maire** (menu 3D). Le Maire porte une couronne et reçoit
une **cloche** (clic droit) pour :
- **lancer le vote maintenant** pendant le débat (fini les débats interminables),
- **prolonger le débat** de 30 s (2 fois max),
- **clore le vote** dès qu'il le juge (aussi en bouton dans son menu de vote).

Sa voix **compte double** et, en cas d'égalité, **son choix l'emporte**. S'il meurt, il désigne son successeur
(20 s, sinon tirage au sort). Tout se règle dans la section `mayor` de `config.yml`.

## Spectateurs

Les morts regardent la partie **à travers les yeux des survivants** (vue à la 1re personne) :
le rôle du joueur suivi s'affiche à l'écran et ils voient même ses menus 3D.
**Clic gauche** = joueur suivant, **shift** = joueur précédent.
Quelqu'un hors partie peut regarder avec `/lg spectate <arène>` ou en cliquant une arène « en cours »
dans le menu des arènes ; `/lg leave` pour arrêter. Le chat des spectateurs n'est vu que des morts.

## Mise en scène

- **Nuit** : le ciel s'assombrit doucement avant que le village s'endorme, braises et fumée au centre,
  cendres qui tombent, hurlements et bruits de la nuit. Le soleil se lève à l'aube.
- **Votes en 3D** : un rayon de particules relie chaque votant à sa cible et le nombre de voix flotte
  au-dessus de chaque joueur (vote du village, élection du Maire ; les votes des loups ne sont vus que par les loups).
- **Mort** : éclair sur la place du joueur éliminé.
- **Fin de partie** : résumé 3D devant chaque joueur — toutes les cartes (survivants, gagnants, Maire, amoureux)
  et le déroulé complet de la partie (« Nuit 1 · La Voyante découvre que… »). Durée : `timers.summary`.

## Commandes

| Commande | Rôle |
|---|---|
| `/lg arenes` · `/lg join [arène]` · `/lg leave` | Menu des arènes / rejoindre / quitter |
| `/lg spectate <arène>` | Regarder une partie en cours |
| `/lg carte` | Revoir sa carte (ou clic droit sur la carte, slot 9) |
| `/lg menu` | Rouvrir le menu d'action (ou clic droit sur l'étoile) |
| `/lg roles [n]` | Composition prévue pour n joueurs |
| `/lg arena create\|edit\|done\|delete\|rename\|list\|info` | Admin : gestion des arènes |
| `/lg admin [arène]` | Admin : composition des rôles de l'arène, lancer/arrêter |
| `/lg start` · `forcestart` · `stop` · `skip` `[arène]` | Admin : lancer, forcer (test à 1-2 joueurs), arrêter, passer la phase |
| `/lg test <menu> [rôle\|nombre]` | Admin : ouvre une interface hors partie (vote, maire, pouvoirs, loups, voyante, sorciere, cupidon, chasseur, salvateur, fiche, carte, arenes, admin, fermer) |
| `/lg bot <n> [arène]` · `/lg bot retirer` | Admin : ajouter / retirer des bots de test (avant le lancement) |
| `/lg slot <1-9>` · `setlobby` · `reload` | Admin : case de la tête, monde du hub, recharger config.yml |

Sans nom d'arène, les commandes admin visent l'arène où tu es, sinon la seule existante.

Permission admin : `loupgarou.admin` (op par défaut).

## Menus 3D

Tous les menus de jeu (vote, loups, voyante, sorcière, cupidon, chasseur, salvateur, fiche de rôle)
flottent en 3D devant la caméra, visibles uniquement par le joueur concerné.
Le **viseur sert de curseur** : n'importe quel clic sélectionne l'élément visé. **S'accroupir** ferme le menu,
l'étoile (ou `/lg menu`) le rouvre.

Mise en page des menus de jeu :
- **Bandeau de titre** à la couleur du rôle (« VOTE DU VILLAGE », « LA MEUTE CHOISIT »…), puis la consigne.
- **Une bannière par joueur** : sa tête, son pseudo (Archivo Black) et, pour les votes, une **jauge de voix**
  (pleine à la majorité) avec les mini-têtes de ceux qui ont voté pour lui. Ta cible est en doré.
  1 colonne jusqu'à 4 joueurs, 2 jusqu'à 10, 3 au-delà.
- **Boutons unifiés** faits à partir du bouton vide : vert = valider, rouge = action dangereuse, gris = passer / indisponible,
  orange = clore le vote, doré = pouvoirs du Maire.
- **Sablier** en bas du panneau : la barre se vide en continu et passe au rouge dans les 10 dernières secondes.
- **Survol** : cadre blanc lumineux, petit « tick » et une ligne d'aide sous le panneau (« ▶ Clique pour voter contre Pierre »).
- **Animation** : le panneau se déplie du centre vers les bords à l'ouverture et se replie à la fermeture.

## Bots de test (admin)

Pour tester une partie complète sans avoir 8 joueurs :
1. Rejoins l'arène (tête de loup), puis `/lg bot 7` (ou `/lg bot 7 village` pour viser une arène).
2. `/lg forcestart` (ou attends le lancement automatique).

Les bots sont des **mannequins** posés sur leurs spawns, avec leur nom au-dessus. Ils reçoivent un vrai rôle et jouent
seuls après quelques secondes de « réflexion » : les loups votent (et suivent la meute), la voyante sonde, le salvateur
protège, la sorcière sauve parfois, Cupidon choisit, tout le monde vote pour le Maire et au village, le Chasseur tire au hasard.
Ils apparaissent dans les menus et les rayons de vote, et les spectateurs peuvent se mettre dans leur vue.
`/lg bot retirer` les enlève avant le lancement ; ils disparaissent à la fin de la partie. `/lg skip` pour accélérer le débat.
Le choix des arènes est aussi en 3D ; seul le menu admin (`/lg admin`) reste un inventaire.

## Déroulement

- **Distribution** : dos de carte puis retournement animé → ton rôle en grand au centre de l'écran.
- **Nuit** (cécité pour tous) : Cupidon (nuit 1) → Amoureux → Voyante → Salvateur → Loups → Sorcière.
  Seul le rôle appelé est réveillé et reçoit son menu. Les rôles morts sont quand même « appelés »
  pour ne rien révéler (`fake-steps-for-dead-roles`).
- **Jour** : révélation des morts (carte retournée pour tout le monde), élection du Maire (jour 1), débat, puis vote dans le menu
  3D — têtes des joueurs, nombre de voix en direct, votants listés, abstention possible.
  Égalité = le Maire départage, sinon personne n'est éliminé.
- **Chasseur** : à sa mort, menu de dernier tir. **Amoureux** : mort de chagrin en chaîne.
- **Victoire** : Village (plus de loups), Loups (≥ moitié), Amoureux (seuls survivants).

Rôles : Loup-Garou, Villageois, Voyante, Sorcière, Chasseur, Cupidon, Salvateur.
Composition : automatique (1 loup pour 4 joueurs) ou manuelle via `/lg admin` — clic gauche +1, clic droit -1 sur chaque carte. Les places restantes deviennent des Villageois ; le lancement est bloqué s'il y a trop de cartes ou de loups.

## Sans risque pour le reste du serveur

Inventaire, position, mode de jeu, vie, XP et effets sont sauvegardés au lancement et rendus à la fin
(ou si le joueur quitte). L'heure de nuit est par joueur : le monde n'est pas modifié.
Seuls les inscrits sont affectés par les règles (gel, chat, dégâts).

## Modifier les visuels

`python3 tools/generate_pack.py` (Pillow) régénère les cartes, les fonds de menus et le zip.
Les images du menu des arènes sont dans `tools/assets/` (bannière, boutons, flèches, `btn_vide.png` pour QUITTER / COMPLET / BIENTÔT, police `ArchivoBlack-Regular.ttf` pour les boutons et les textes du menu).
L'ordre des rôles/thèmes dans le script doit rester identique à `Role.java` et `MenuTheme.java`.

Si Minecraft affiche « pack conçu pour une autre version », ajuste `pack_format` / `min_format`
/ `max_format` dans `pack.mcmeta` ; le contenu (police bitmap, `items/`) est compatible 1.21.4+.
