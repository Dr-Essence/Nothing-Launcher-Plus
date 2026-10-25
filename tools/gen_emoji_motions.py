#!/usr/bin/env python3
"""Generates app/src/main/assets/emoji_motions.tsv: every emoji with its picker group and motion.

Source data: emojibase-data (MIT), en/data.json + meta/groups.json, e.g. from
`npm pack emojibase-data@16.0.3`. Usage:
    python3 tools/gen_emoji_motions.py <emojibase package dir> <repo root>

Each emoji gets the first match of: an exact override by CLDR name, a name keyword rule, its
subgroup default, its group default. Motion names are checked against the Motion enum.
"""
import json
import os
import re
import sys

EXACT = {
    # Smiling and affection
    "grinning face": "HAPPY", "grinning face with big eyes": "HAPPY",
    "grinning face with smiling eyes": "HAPPY", "beaming face with smiling eyes": "GIGGLE",
    "grinning squinting face": "LAUGH", "grinning face with sweat": "SHIVER",
    "rolling on the floor laughing": "LAUGH", "face with tears of joy": "LAUGH",
    "slightly smiling face": "BOB", "upside-down face": "FLIP", "melting face": "MELT",
    "winking face": "TILT", "smiling face with smiling eyes": "BOB",
    "smiling face with halo": "FLOAT", "smiling face with hearts": "LOVE",
    "smiling face with heart-eyes": "LOVE", "star-struck": "TWINKLE",
    "face blowing a kiss": "KISS", "kissing face": "KISS", "smiling face": "BOB",
    "kissing face with closed eyes": "KISS", "kissing face with smiling eyes": "KISS",
    "smiling face with tear": "DROOP",
    # Tongue and hands
    "face savoring food": "JELLY", "face with tongue": "JELLY",
    "winking face with tongue": "JELLY", "zany face": "DIZZY",
    "squinting face with tongue": "GIGGLE", "money-mouth face": "COIN",
    "smiling face with open hands": "POP", "face with hand over mouth": "PEEK",
    "face with open eyes and hand over mouth": "PEEK", "face with peeking eye": "PEEK",
    "shushing face": "NOD", "thinking face": "TILT", "saluting face": "NOD",
    # Neutral and skeptical
    "zipper-mouth face": "NOD", "face with raised eyebrow": "TILT", "neutral face": "BLINK",
    "expressionless face": "BLINK", "face without mouth": "BLINK", "dotted line face": "GHOST",
    "face in clouds": "FLOAT", "smirking face": "ROLL", "unamused face": "ROLL",
    "face with rolling eyes": "ROLL", "grimacing face": "SHIVER", "face exhaling": "WHOOSH",
    "lying face": "PULSE", "shaking face": "BUZZ", "head shaking horizontally": "NO",
    "head shaking vertically": "NOD",
    # Sleepy and unwell
    "relieved face": "BREATHE", "pensive face": "DROOP", "sleepy face": "SLEEP",
    "drooling face": "JELLY", "sleeping face": "SLEEP", "face with bags under eyes": "SLEEP",
    "face with medical mask": "WOOZY", "face with thermometer": "WOOZY",
    "face with head-bandage": "WOOZY", "nauseated face": "WOOZY", "face vomiting": "WOOZY",
    "sneezing face": "SNEEZE", "hot face": "MELT", "cold face": "SHIVER", "woozy face": "WOOZY",
    "face with crossed-out eyes": "DIZZY", "face with spiral eyes": "DIZZY",
    "exploding head": "EXPLODE",
    # Hats and glasses
    "cowboy hat face": "TILT", "partying face": "PARTY", "disguised face": "COOL",
    "smiling face with sunglasses": "COOL", "nerd face": "NOD", "face with monocle": "SEARCH",
    # Concerned
    "confused face": "TILT", "face with diagonal mouth": "TILT", "worried face": "DROOP",
    "slightly frowning face": "DROOP", "frowning face": "DROOP",
    "face with open mouth": "SURPRISE", "hushed face": "SURPRISE",
    "astonished face": "SURPRISE", "flushed face": "SURPRISE", "pleading face": "PULSE",
    "face holding back tears": "SOB", "frowning face with open mouth": "DROOP",
    "anguished face": "SHIVER", "fearful face": "SHIVER", "anxious face with sweat": "SHIVER",
    "sad but relieved face": "DROOP", "crying face": "SOB", "loudly crying face": "SOB",
    "face screaming in fear": "SCREAM", "confounded face": "SHIVER",
    "persevering face": "SHIVER", "disappointed face": "DROOP",
    "downcast face with sweat": "DROOP", "weary face": "DROOP", "tired face": "DROOP",
    "yawning face": "SLEEP",
    # Negative and costume
    "face with steam from nose": "RAGE", "enraged face": "RAGE", "angry face": "RAGE",
    "face with symbols on mouth": "RAGE", "smiling face with horns": "TILT",
    "angry face with horns": "RAGE", "skull": "GHOST", "skull and crossbones": "GHOST",
    "pile of poo": "JELLY", "clown face": "GIGGLE", "ogre": "RAGE", "goblin": "RAGE",
    "ghost": "GHOST", "alien": "FLOAT", "alien monster": "ROBOT", "robot": "ROBOT",
    # Cats and monkeys
    "grinning cat": "HAPPY", "grinning cat with smiling eyes": "GIGGLE",
    "cat with tears of joy": "LAUGH", "smiling cat with heart-eyes": "LOVE",
    "cat with wry smile": "ROLL", "kissing cat": "KISS", "weary cat": "SCREAM",
    "crying cat": "SOB", "pouting cat": "RAGE",
    # Hearts and emotion
    "broken heart": "DROOP", "mending heart": "PULSE", "sparkling heart": "TWINKLE",
    "growing heart": "GROW", "revolving hearts": "SPIN", "two hearts": "LOVE",
    "heart on fire": "FLICKER", "kiss mark": "KISS", "hundred points": "STAMP",
    "anger symbol": "RAGE", "collision": "EXPLODE", "dizzy": "SPIN",
    "sweat droplets": "DRIP", "dashing away": "WHOOSH", "hole": "PULSE",
    "speech balloon": "FLOAT", "eye in speech bubble": "BLINK",
    "left speech bubble": "FLOAT", "right anger bubble": "RAGE", "thought balloon": "FLOAT",
    "zzz": "SLEEP",
    # Sky
    "sun": "TURN", "sun with face": "TURN", "high voltage": "FLASH", "rainbow": "FLOAT",
    "snowman": "ROCK", "snowman without snow": "ROCK", "comet": "WHOOSH",
    "shooting star": "WHOOSH", "droplet": "DRIP", "water wave": "ROCK",
    # Objects worth singling out
    "wrapped gift": "RATTLE", "video game": "BUZZ", "joystick": "BUZZ", "gear": "SPIN",
    "light bulb": "FLASH", "hourglass done": "FLIP", "hourglass not done": "FLIP",
    "alarm clock": "ALARM", "chart increasing": "GROW", "chart decreasing": "SHRINK",
    "chart increasing with yen": "GROW", "bomb": "EXPLODE", "magic wand": "TWINKLE",
    "crystal ball": "TWINKLE", "balloon": "FLOAT", "rocket": "LAUNCH",
    "flying saucer": "FLY", "ferris wheel": "SPIN", "carousel horse": "HOP",
    "eyes": "PEEK", "eye": "BLINK", "brain": "PULSE", "anatomical heart": "HEARTBEAT",
    "lungs": "BREATHE", "speaking head": "NOD", "footprints": "RUN",
    "leg": "PUNCH", "foot": "PUNCH", "mechanical arm": "FLEX", "flexed biceps": "FLEX",
    # Names that would otherwise hit the wrong keyword rule
    "heart exclamation": "HEARTBEAT", "rice ball": "JELLY", "confetti ball": "PARTY",
    "fire engine": "DRIVE", "fire extinguisher": "PULSE", "moon cake": "JELLY",
    "hair pick": "PULSE", "musical keyboard": "GROOVE", "palm tree": "SWAY",
    "bell pepper": "JELLY", "selfie": "NOD", "oil drum": "SETTLE", "kite": "FLY",
}

# (regex on the lower-case CLDR name, motion); first match wins.
RULES = [
    (r"^kiss|couple with heart", "LOVE"),
    (r"facepalm", "DROOP"),
    (r"massage|haircut", "BREATHE"),
    (r"standing", "NOD"),
    (r"worker|officer|guard|detective|technologist|scientist|astronaut|pilot|judge|farmer"
     r"|\bcook\b|mechanic|singer|teacher|student|firefighter|health worker|construction"
     r"|ninja|prince|princess|royalty|person with crown", "NOD"),
    (r"waving hand", "WAVE"),
    (r"thumbs up", "THUMB_UP"),
    (r"thumbs down", "THUMB_DOWN"),
    (r"clapping", "CLAP"),
    (r"raising hands", "HOORAY"),
    (r"folded hands", "PRAY"),
    (r"writing hand", "WRITE"),
    (r"pointing up|index pointing up|up arrow|upwards|top arrow|up-pointing", "POINT_UP"),
    (r"pointing down|down arrow|downwards|down-pointing", "POINT_DOWN"),
    (r"pointing left|left arrow|leftwards|back arrow", "POINT_LEFT"),
    (r"pointing right|right arrow|rightwards|soon arrow", "POINT_RIGHT"),
    (r"pointing at the viewer", "POINT_AT"),
    (r"fist", "PUNCH"),
    (r"ok hand|pinch", "PINCH"),
    (r"victory|crossed fingers|love-you|sign of the horns|call me", "ROCK_ON"),
    (r"raised hand|fingers splayed|vulcan|\bpalms? (up|down)|hand with index finger and thumb",
     "HIGH_FIVE"),
    (r"handshake", "NOD"),
    (r"dancing|dancer|bunny ears", "DANCE"),
    (r"running|walking|kneeling|person climbing|fencing|horse racing|skier|snowboarder|surfing"
     r"|rowing|swimming|bouncing ball|lifting weights|biking|cartwheel|wrestling|water polo"
     r"|handball|juggling", "RUN"),
    (r"lotus position|in bed|in steamy room|bath", "BREATHE"),
    (r"zombie|vampire|\bmage\b|fairy|genie|merperson|mermaid|merman|superhero|supervillain|\belf\b"
     r"|troll", "FLOAT"),
    (r"ghost", "GHOST"),
    (r"\bfire\b|candle|diya lamp|lantern|flashlight", "FLICKER"),
    (r"sparkle|\bstar\b|glowing star|dizzy", "TWINKLE"),
    (r"camera|flash|high voltage|light bulb|vertical traffic light|horizontal traffic light",
     "FLASH"),
    (r"moon", "ROCK"),
    (r"cloud|rainbow|balloon|bubbles|fog", "FLOAT"),
    (r"snowflake|cyclone|tornado|recycling|clockwise|anticlockwise|counterclockwise"
     r"|repeat button", "SPIN"),
    (r"droplet|drop of blood|water wave", "DRIP"),
    (r"wind face|dashing|dash", "WHOOSH"),
    (r"collision|bomb|exploding|firecracker|fireworks|sparkler", "EXPLODE"),
    (r"cross mark|prohibited|no entry|\bno \w|^no ", "NO"),
    (r"check mark|check box", "POP"),
    (r"warning|exclamation|police car light|siren|radioactive|biohazard", "ALERT"),
    (r"question mark", "WONDER"),
    (r"\bbell\b|bellhop", "RING"),
    (r"hourglass", "FLIP"),
    (r"o’clock|o'clock|thirty|stopwatch|timer clock|mantelpiece clock|\bwatch\b", "TICK"),
    (r"mobile phone|telephone|pager|fax machine|vibration mode", "BUZZ"),
    (r"laptop|keyboard|desktop computer|computer mouse|trackball|printer|floppy|optical disk"
     r"|\bdvd\b|abacus", "TYPE"),
    (r"money|dollar|\byen\b|\beuro\b|pound banknote|coin|credit card|receipt|gem stone"
     r"|currency", "COIN"),
    (r"\bkey\b|old key|locked|unlocked|\block\b", "UNLOCK"),
    (r"hammer|wrench|screwdriver|\baxe\b|\bpick\b|carpentry saw|nut and bolt|toolbox|shovel"
     r"|crossed swords|dagger|sword|shield|bow and arrow|boomerang|water pistol|clamp"
     r"|chains|ladder", "HAMMER"),
    (r"scissors", "SNIP"),
    (r"magnifying glass", "SEARCH"),
    (r"envelope|e-mail|incoming|outbox tray|inbox tray|postbox|mailbox|package|love letter"
     r"|postal horn", "SEND"),
    (r"rocket", "LAUNCH"),
    (r"airplane|helicopter|parachute|flying saucer|satellite|aerial tramway|mountain cableway"
     r"|suspension railway", "FLY"),
    (r"ship|boat|canoe|sailboat|ferry|anchor", "ROCK"),
    (r"\bcar\b|automobile|\bbus\b|taxi|truck|tractor|motorcycle|scooter|bicycle|train"
     r"|\btram\b|locomotive|railway car|metro|monorail|skateboard|roller skate|racing car"
     r"|ambulance|fire engine|police car|trolleybus|minibus|motor boat|wheelchair"
     r"|auto rickshaw|pickup truck|\bvan\b|station", "DRIVE"),
    (r"\bball\b|basketball|football|baseball|softball|volleyball|tennis|rugby|bowling"
     r"|ping pong|badminton|soccer|cricket game|field hockey|ice hockey|lacrosse|curling"
     r"|yo-yo|kite", "BOUNCE"),
    (r"trophy|medal|place medal|military medal|reminder ribbon|rosette", "POP"),
    (r"party popper|confetti|birthday|tanabata|pine decoration|jack-o-lantern"
     r"|christmas tree|wind chime|moon viewing|carp streamer|red envelope|ticket"
     r"|admission", "PARTY"),
    (r"\bdrum\b|long drum", "DRUM"),
    (r"musical|music|notes|microphone|headphone|guitar|piano|violin|trumpet|saxophone|banjo"
     r"|accordion|maracas|flute|harp|radio|studio|control knobs|level slider", "GROOVE"),
    (r"speaker|megaphone|loudspeaker", "PULSE"),
    (r"globe|world map|map of japan", "GLOBE"),
    (r"flag|chequered", "FLAG"),
    (r"chart increasing|bar chart", "GROW"),
    (r"chart decreasing", "SHRINK"),
    (r"fallen leaf|maple leaf|leaf fluttering|feather", "DRIFT"),
    (r"butterfly|\bbat\b|honeybee|\bfly\b|mosquito|lady beetle|beetle|cricket|cockroach", "FLUTTER"),
    (r"bird|eagle|duck|owl|parrot|penguin|dove|swan|flamingo|peacock|rooster|chicken|chick"
     r"|turkey|dodo|goose|phoenix", "FLAP"),
    (r"fish|dolphin|whale|shark|octopus|squid|shrimp|lobster|crab|oyster|jellyfish|seal"
     r"|coral|spiral shell", "SWIM"),
    (r"snake|dragon|lizard|crocodile|turtle|sauropod|t-rex", "SLITHER"),
    (r"snail|caterpillar|\bbug\b|worm|\bant\b|microbe|spider|scorpion", "CRAWL"),
    (r"\bcup\b|teacup|coffee|\btea\b|beer|wine|cocktail|tropical drink|bottle"
     r"|glass(?!es)\b|clinking glasses|milk|juice"
     r"|\bmate\b|sake|bubble tea|clinking|tumbler|beverage box|baby bottle", "SIP"),
    (r"book|notebook|ledger|page|scroll|newspaper|bookmark|memo|clipboard|calendar"
     r"|spiral notepad|card index", "PAGE"),
    (r"pencil|\bpen\b|crayon|paintbrush|fountain pen|palette|artist", "WRITE"),
    (r"house|building|office|hospital|\bbank\b|hotel|school|castle|church|mosque|temple"
     r"|stadium|factory|tower|statue|\bhut\b|wedding|synagogue|kaaba|shrine|department store"
     r"|convenience store|love hotel|post office|classical|bridge|fountain|tent|cityscape"
     r"|sunrise|sunset|night with stars|milky way", "SETTLE"),
    (r"volcano|mountain|camping|beach|desert|island|national park|rock\b|wood\b", "SETTLE"),
    (r"umbrella|rain|thunder|snowman|comet", "ROCK"),
    (r"teddy bear|nesting dolls|piñata|pinata|mirror ball", "ROCK"),
    (r"game die|puzzle|chess|mahjong|flower playing cards|joker|slot machine|\bspade\b"
     r"|\bheart suit\b|\bdiamond suit\b|\bclub suit\b|direct hit|pool 8 ball|bingo", "RATTLE"),
    (r"magnet|battery|electric plug|syringe|\bpill\b|adhesive bandage|stethoscope|\bdna\b"
     r"|test tube|petri dish|microscope|telescope", "PULSE"),
    (r"heart", "HEARTBEAT"),
]

SUBGROUP_DEFAULT = {
    "face-smiling": "BOB", "face-affection": "LOVE", "face-tongue": "JELLY",
    "face-hand": "PEEK", "face-neutral-skeptical": "TILT", "face-sleepy": "SLEEP",
    "face-unwell": "WOOZY", "face-hat": "PARTY", "face-glasses": "COOL",
    "face-concerned": "DROOP", "face-negative": "RAGE", "face-costume": "GHOST",
    "cat-face": "BOB", "monkey-face": "PEEK", "heart": "HEARTBEAT", "emotion": "PULSE",
    "hand-fingers-open": "WAVE", "hand-fingers-partial": "ROCK_ON",
    "hand-single-finger": "POINT_UP", "hand-fingers-closed": "PUNCH", "hands": "CLAP",
    "hand-prop": "WRITE", "body-parts": "PULSE", "person": "NOD", "person-gesture": "NOD",
    "person-role": "NOD", "person-fantasy": "FLOAT", "person-activity": "RUN",
    "person-sport": "RUN", "person-resting": "BREATHE", "family": "BOB",
    "person-symbol": "PULSE", "skin-tone": "PULSE", "hair-style": "PULSE",
    "animal-mammal": "HOP", "animal-bird": "FLAP", "animal-amphibian": "HOP",
    "animal-reptile": "SLITHER", "animal-marine": "SWIM", "animal-bug": "CRAWL",
    "plant-flower": "SWAY", "plant-other": "SWAY", "food-fruit": "JELLY",
    "food-vegetable": "JELLY", "food-prepared": "JELLY", "food-asian": "JELLY",
    "food-sweet": "JELLY", "drink": "SIP", "dishware": "RATTLE", "place-map": "GLOBE",
    "place-geographic": "SETTLE", "place-building": "SETTLE", "place-religious": "SETTLE",
    "place-other": "SETTLE", "transport-ground": "DRIVE", "transport-water": "ROCK",
    "transport-air": "FLY", "hotel": "RATTLE", "time": "TICK", "sky-weather": "FLOAT",
    "event": "PARTY", "award-medal": "POP", "sport": "BOUNCE", "game": "RATTLE",
    "arts-crafts": "WRITE", "clothing": "SWAY", "sound": "RING", "music": "GROOVE",
    "musical-instrument": "GROOVE", "phone": "BUZZ", "computer": "TYPE",
    "light-video": "FLASH", "book-paper": "PAGE", "money": "COIN", "mail": "SEND",
    "writing": "WRITE", "office": "HOP", "lock": "UNLOCK", "tool": "HAMMER",
    "science": "PULSE", "medical": "PULSE", "household": "SETTLE", "other-object": "PULSE",
    "transport-sign": "PULSE", "warning": "ALERT", "arrow": "PULSE", "religion": "PULSE",
    "zodiac": "TWINKLE", "av-symbol": "PULSE", "gender": "PULSE", "math": "POP",
    "punctuation": "WONDER", "currency": "COIN", "other-symbol": "POP", "keycap": "POP",
    "alphanum": "POP", "geometric": "PULSE", "flag": "FLAG", "country-flag": "FLAG",
    "subdivision-flag": "FLAG",
}

# Picker tabs, in display order (emojibase group ids); "component" is not pickable.
PICKER_GROUPS = [0, 1, 3, 4, 5, 6, 7, 8, 9]


def motion_names(repo):
    src = open(os.path.join(repo, "app/src/main/java/com/dressence/nothinglauncherplus/anim/"
                                  "Motion.java"), encoding="utf-8").read()
    return set(re.findall(r"^    ([A-Z_]+)\(\"", src, re.M))


def main(data_dir, repo):
    data = json.load(open(os.path.join(data_dir, "en/data.json"), encoding="utf-8"))
    meta = json.load(open(os.path.join(data_dir, "meta/groups.json"), encoding="utf-8"))
    subgroups = meta["subgroups"]
    known = motion_names(repo)
    rules = [(re.compile(p), m) for p, m in RULES]
    used = {m for m in list(EXACT.values()) + [m for _, m in RULES]
            + list(SUBGROUP_DEFAULT.values())}
    unknown = used - known
    if unknown:
        sys.exit("unknown motions: %s" % sorted(unknown))

    lines = []
    stats = {"exact": 0, "rule": 0, "subgroup": 0}
    for e in sorted(data, key=lambda e: e.get("order", 1 << 30)):
        group = e.get("group")
        if group not in PICKER_GROUPS:
            continue
        label = e["label"].lower()
        if label in EXACT:
            motion, how = EXACT[label], "exact"
        else:
            motion = next((m for p, m in rules if p.search(label)), None)
            how = "rule"
            if motion is None:
                motion, how = SUBGROUP_DEFAULT[subgroups[str(e["subgroup"])]], "subgroup"
        stats[how] += 1
        lines.append("%s\t%d\t%s\t%s" % (e["emoji"], PICKER_GROUPS.index(group), motion,
                                         e["label"]))

    out = os.path.join(repo, "app/src/main/assets/emoji_motions.tsv")
    with open(out, "w", encoding="utf-8") as f:
        f.write("# Generated by tools/gen_emoji_motions.py from emojibase-data (MIT).\n")
        f.write("# emoji<TAB>picker group<TAB>motion<TAB>name\n")
        f.write("\n".join(lines) + "\n")
    used_motions = {l.split("\t")[2] for l in lines}
    print("emojis=%d %s motions used=%d/%d" % (len(lines), stats, len(used_motions),
                                               len(known) - 1))
    print("never used:", sorted(known - used_motions - {"NONE"}))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
