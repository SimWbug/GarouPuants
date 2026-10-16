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
3. En jeu (op) : place-toi au centre de la table → `/lg setcenter`, puis `/lg setlobby`.

## Commandes

| Commande | Rôle |
|---|---|
| `/lg join` / `/lg leave` | Rejoindre / quitter |
| `/lg carte` | Revoir sa carte (ou clic droit sur la carte, slot 9) |
| `/lg menu` | Rouvrir le menu d'action (ou clic droit sur l'étoile) |
| `/lg roles [n]` | Composition prévue pour n joueurs |
| `/lg admin` | Admin : menu de composition (nombre de cartes par rôle, mode auto/manuel, lancer/arrêter) |
| `/lg test <menu> [rôle\|nombre]` | Admin : ouvre une interface hors partie avec de faux joueurs (vote, loups, voyante, sorciere, cupidon, chasseur, salvateur, fiche, carte, admin, fermer) |
| `/lg start` · `forcestart` · `stop` · `skip` | Admin : lancer, forcer (test à 1-2 joueurs), arrêter, passer la phase |
| `/lg setcenter` · `setlobby` · `reload` | Admin : configuration |

Permission admin : `loupgarou.admin` (op par défaut).

## Menus 3D

Tous les menus de jeu (vote, loups, voyante, sorcière, cupidon, chasseur, salvateur, fiche de rôle)
flottent en 3D devant la caméra, visibles uniquement par le joueur concerné.
Le **viseur sert de curseur** : l'élément visé grossit, s'illumine et affiche ses détails ;
n'importe quel clic le sélectionne. **S'accroupir** ferme le menu, l'étoile (ou `/lg menu`) le rouvre.
Seul le menu admin (`/lg admin`) reste un inventaire.

## Déroulement

- **Distribution** : dos de carte puis retournement animé → ton rôle en grand au centre de l'écran.
- **Nuit** (cécité pour tous) : Cupidon (nuit 1) → Amoureux → Voyante → Salvateur → Loups → Sorcière.
  Seul le rôle appelé est réveillé et reçoit son menu. Les rôles morts sont quand même « appelés »
  pour ne rien révéler (`fake-steps-for-dead-roles`).
- **Jour** : révélation des morts (carte retournée pour tout le monde), débat, puis vote dans le menu
  3D — têtes des joueurs, nombre de voix en direct, votants listés, abstention possible.
  Égalité = personne n'est éliminé.
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
L'ordre des rôles/thèmes dans le script doit rester identique à `Role.java` et `MenuTheme.java`.

Si Minecraft affiche « pack conçu pour une autre version », ajuste `pack_format` / `min_format`
/ `max_format` dans `pack.mcmeta` ; le contenu (police bitmap, `items/`) est compatible 1.21.4+.
