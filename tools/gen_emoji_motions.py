#!/usr/bin/env python3
"""Generates app/src/main/assets/emoji_motions.tsv: every emoji with its picker group and motion.

Source data: emojibase-data (MIT), en/data.json + meta/groups.json, e.g. from
`npm pack emojibase-data@16.0.3`. Usage:
    python3 tools/gen_emoji_motions.py <emojibase package dir> <repo root>

Each emoji gets a motion (how the whole emoji moves) and an effect (what happens inside its
dots: static on a TV, tears running, a blink), each from the first match of: an exact override
by CLDR name, a name keyword rule, its subgroup default. Names are checked against the Motion
and DotEffect enums.
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

# ---------------------------------------------------------------------------------------------
# Effects: what plays inside the dots.
# ---------------------------------------------------------------------------------------------

EFFECT_EXACT = {
    # Faces with water on them: the water runs.
    "grinning face with sweat": "SWEAT", "anxious face with sweat": "SWEAT",
    "downcast face with sweat": "SWEAT", "face with tears of joy": "TEARS",
    "rolling on the floor laughing": "TEARS", "smiling face with tear": "TEARS",
    "crying face": "TEARS", "loudly crying face": "TEARS", "face holding back tears": "TEARS",
    "drooling face": "DRIP", "sneezing face": "DRIP", "hot face": "SWEAT",
    # Love and wonder
    "smiling face with hearts": "HEARTS", "smiling face with heart-eyes": "HEARTS",
    "face blowing a kiss": "HEARTS", "kissing face": "HEARTS",
    "kissing face with closed eyes": "HEARTS", "kissing face with smiling eyes": "HEARTS",
    "cat with wry smile": "BLINK", "smiling cat with heart-eyes": "HEARTS",
    "kissing cat": "HEARTS", "star-struck": "STAR_EYES", "smiling face with halo": "SHINE",
    # Sleepy, hot-headed, dizzy
    "sleeping face": "SLEEPY", "sleepy face": "SLEEPY", "yawning face": "SLEEPY",
    "face with bags under eyes": "SLEEPY", "pouting face": "ANGRY", "angry face": "ANGRY",
    "enraged face": "ANGRY", "face with symbols on mouth": "ANGRY",
    "face with steam from nose": "STEAM", "smiling face with horns": "FLAME",
    "angry face with horns": "FLAME", "exploding head": "FIREWORKS",
    "face exhaling": "SMOKE", "face in clouds": "SMOKE", "partying face": "CONFETTI",
    "cold face": "SNOW", "shaking face": "ZAP", "face with spiral eyes": "TWINKLE",
    "dizzy face": "TWINKLE", "face with crossed-out eyes": "TWINKLE",
    "money-mouth face": "SHINE", "smiling face with sunglasses": "SHINE",
    "face with peeking eye": "LOOK", "face with rolling eyes": "LOOK",
    "face with monocle": "LOOK", "dotted line face": "GHOSTLY", "ghost": "GHOSTLY",
    "skull": "GHOSTLY", "skull and crossbones": "GHOSTLY", "alien": "GHOSTLY",
    "alien monster": "DIGITAL", "robot": "DIGITAL", "pile of poo": "SMOKE",
    "clown face": "CONFETTI", "ogre": "ANGRY", "goblin": "ANGRY", "jack-o-lantern": "FLAME",
    # Eyes and minds
    "eyes": "LOOK", "eye": "LOOK", "brain": "ZAP", "anatomical heart": "HEARTBEAT",
    "lungs": "SMOKE", "mouth": "HEARTS", "biting lip": "HEARTS", "speaking head": "SOUND",
    # Emotions
    "anger symbol": "ANGRY", "collision": "FIREWORKS", "dizzy": "TWINKLE",
    "sweat droplets": "DRIP", "dashing away": "SMOKE", "zzz": "SLEEPY", "hole": "MAGIC",
    "speech balloon": "DIGITAL", "eye in speech bubble": "LOOK", "left speech bubble": "DIGITAL",
    "right anger bubble": "ANGRY", "thought balloon": "MAGIC", "kiss mark": "HEARTS",
    "love letter": "HEARTS", "hundred points": "SHINE", "fire": "FLAME",
    # Screens and signals
    "television": "STATIC", "video camera": "SCREEN", "movie camera": "SCREEN",
    "film projector": "SCREEN", "videocassette": "STATIC", "camera": "BEACON",
    "camera with flash": "BEACON", "laptop": "SCREEN", "desktop computer": "SCREEN",
    "mobile phone": "SCREEN", "mobile phone with arrow": "SCREEN", "pager": "DIGITAL",
    "calculator": "DIGITAL", "fax machine": "DIGITAL", "video game": "DIGITAL",
    "joystick": "DIGITAL", "satellite antenna": "SOUND", "radio": "NOTES",
    "crystal ball": "MAGIC", "magic wand": "MAGIC", "nazar amulet": "MAGIC",
    "hamsa": "MAGIC", "pool 8 ball": "MAGIC", "game die": "SPARKLE", "slot machine": "BEACON",
    "light bulb": "GLOW", "flashlight": "GLOW", "candle": "FLAME", "diya lamp": "FLAME",
    "red paper lantern": "GLOW", "police car light": "BEACON", "rotating light": "BEACON",
    "battery": "ZAP", "low battery": "ZAP", "electric plug": "ZAP", "high voltage": "ZAP",
    "magnet": "ZAP", "satellite": "TWINKLE", "rocket": "FLAME", "flying saucer": "BEACON",
    "fire extinguisher": "SMOKE", "bomb": "FLAME", "firecracker": "FIREWORKS",
    "fireworks": "FIREWORKS", "sparkler": "FIREWORKS", "party popper": "CONFETTI",
    "confetti ball": "CONFETTI", "balloon": "SHINE", "wrapped gift": "SPARKLE",
    "sparkles": "TWINKLE", "glowing star": "TWINKLE", "star": "TWINKLE",
    "shooting star": "TWINKLE", "milky way": "TWINKLE", "night with stars": "WINDOWS",
    "cityscape": "WINDOWS", "cityscape at dusk": "WINDOWS", "office building": "WINDOWS",
    "hospital": "WINDOWS", "hotel": "WINDOWS", "department store": "WINDOWS",
    "school": "WINDOWS", "bank": "WINDOWS", "convenience store": "WINDOWS",
    "love hotel": "HEARTS", "factory": "SMOKE", "volcano": "SMOKE", "locomotive": "SMOKE",
    "ferris wheel": "TWINKLE", "carousel horse": "NOTES", "circus tent": "CONFETTI",
    "bell": "SOUND", "bellhop bell": "SOUND", "loudspeaker": "SOUND", "megaphone": "SOUND",
    "postal horn": "NOTES", "speaker high volume": "SOUND", "speaker medium volume": "SOUND",
    "speaker low volume": "SOUND", "studio microphone": "NOTES", "microphone": "NOTES",
    "headphone": "NOTES", "musical note": "NOTES", "musical notes": "NOTES",
    "musical score": "NOTES", "alarm clock": "CLOCK", "stopwatch": "CLOCK",
    "timer clock": "CLOCK", "mantelpiece clock": "CLOCK", "hourglass done": "CLOCK",
    "hourglass not done": "CLOCK", "watch": "CLOCK",
    # Water, sky, weather
    "droplet": "DRIP", "water wave": "RIPPLE", "ocean": "RIPPLE", "rainbow": "RAINBOW",
    "rainbow flag": "RAINBOW", "umbrella with rain drops": "RAIN", "cloud with rain": "RAIN",
    "cloud with lightning and rain": "RAIN", "cloud with lightning": "ZAP",
    "cloud with snow": "SNOW", "snowflake": "SNOW", "snowman": "SNOW",
    "snowman without snow": "SNOW", "tornado": "SMOKE", "fog": "SMOKE", "foggy": "SMOKE",
    "wind face": "SMOKE", "cloud": "SMOKE", "sun": "GLOW", "sun with face": "GLOW",
    "sun behind cloud": "GLOW", "crescent moon": "TWINKLE", "full moon face": "GLOW",
    "new moon face": "BLINK", "comet": "FLAME", "ringed planet": "TWINKLE",
    "globe showing europe-africa": "RIPPLE", "globe showing americas": "RIPPLE",
    "globe showing asia-australia": "RIPPLE", "globe with meridians": "DIGITAL",
    "fountain": "RIPPLE", "water pistol": "DRIP", "bathtub": "BUBBLES", "shower": "RAIN",
    "soap": "BUBBLES", "bubbles": "BUBBLES", "potable water": "DRIP", "fishing pole": "RIPPLE",
    "hot springs": "STEAM", "sweat droplets": "DRIP", "diving mask": "BUBBLES",
    # Food and drink that steams, fizzes or melts
    "hot beverage": "STEAM", "teacup without handle": "STEAM", "steaming bowl": "STEAM",
    "pot of food": "STEAM", "shallow pan of food": "STEAM", "cooking": "STEAM",
    "hot pepper": "FLAME", "bubble tea": "BUBBLES", "beer mug": "BUBBLES",
    "clinking beer mugs": "BUBBLES", "bottle with popping cork": "CONFETTI",
    "clinking glasses": "SPARKLE", "cup with straw": "BUBBLES", "beverage box": "BUBBLES",
    "mate": "STEAM", "glass of milk": "SHINE", "baby bottle": "BUBBLES",
    "birthday cake": "FLAME", "ice": "SHINE", "shaved ice": "SNOW", "ice cream": "DRIP",
    "soft ice cream": "DRIP", "honey pot": "DRIP", "pouring liquid": "DRIP",
    # Hearts and symbols
    "beating heart": "HEARTBEAT", "growing heart": "HEARTBEAT", "sparkling heart": "TWINKLE",
    "heart with arrow": "HEARTS", "heart with ribbon": "HEARTS", "revolving hearts": "HEARTS",
    "two hearts": "HEARTS", "heart on fire": "FLAME", "mending heart": "SHINE",
    "broken heart": "TEARS", "heart exclamation": "HEARTBEAT", "radioactive": "BEACON",
    "biohazard": "BEACON", "warning": "BEACON", "no entry": "BEACON", "atom symbol": "ZAP",
    "yin yang": "MAGIC", "wheel of dharma": "MAGIC", "infinity": "MAGIC",
    "recycling symbol": "SWAY", "trident emblem": "SHINE", "fleur-de-lis": "SHINE",
    "red question mark": "ZAP", "red exclamation mark": "BEACON", "trophy": "SHINE",
    "gem stone": "SPARKLE", "ring": "SPARKLE", "crown": "SPARKLE", "money bag": "SHINE",
    "coin": "SHINE", "money with wings": "SHINE", "credit card": "SHINE",
    "chart increasing": "DIGITAL", "chart decreasing": "DIGITAL",
    "bar chart": "DIGITAL", "chart increasing with yen": "DIGITAL", "bubbles": "BUBBLES",
    "lollipop": "SPARKLE", "honeybee": "SWAY", "spider web": "SHINE",
    # Animals with something to do
    "fish": "BUBBLES", "tropical fish": "BUBBLES", "blowfish": "BUBBLES", "shark": "RIPPLE",
    "whale": "RIPPLE", "spouting whale": "DRIP", "dolphin": "RIPPLE", "octopus": "BUBBLES",
    "jellyfish": "GHOSTLY", "seal": "RIPPLE", "otter": "RIPPLE", "crab": "BUBBLES",
    "lobster": "BUBBLES", "shrimp": "BUBBLES", "squid": "BUBBLES", "coral": "BUBBLES",
    "spiral shell": "SHINE", "dragon": "FLAME", "dragon face": "FLAME", "phoenix": "FLAME",
    "unicorn": "RAINBOW", "firefly": "GLOW", "butterfly": "SPARKLE", "peacock": "SPARKLE",
}

# Things that make sound, each with its own logic (see anim/MusicEffects.java). Anchor points
# after the colon are fractions of the emoji's drawn bounds (Noto Color Emoji): where a horn's
# narrow end, a bell's clapper or a guitar's bridge and nut are.
EFFECT_MUSIC = {
    # Speakers light their own drawn waves; the quiet one sends a small wave from its mouth.
    "speaker high volume": "SOUND", "speaker medium volume": "SOUND",
    "speaker low volume": "SOUND:0.97,0.3,-30", "speaking head": "SOUND",
    "muted speaker": "MUTED", "bell with slash": "MUTED",
    "bell": "BELL:0.5,0.92", "bellhop bell": "BELL:0.5,0.05",
    "loudspeaker": "HORN:0.1,0.45", "megaphone": "HORN:0.05,0.75",
    "satellite antenna": "SIGNAL:0.8,0.05",
    "musical note": "MELODY", "musical notes": "MELODY", "musical score": "SCORE",
    "headphone": "EQUALIZER:0.33,0.35,0.67,0.95", "radio": "EQUALIZER:0.5,0.48,0.885,0.65",
    "level slider": "EQUALIZER:0.42,0.08,0.58,0.92", "control knobs": "KNOBS",
    "microphone": "MIC:0.78,0.2,0.25", "studio microphone": "MIC:0.5,0.4,0.5",
    "saxophone": "WIND:0.12,0.3,-0.3,-0.95", "trumpet": "WIND:0.1,0.85,-0.2,-1",
    "postal horn": "WIND:0.8,0.4,-0.2,-1", "flute": "WIND:0.75,0.3,0.2,-1",
    "guitar": "STRUM:0.3,0.72,0.92,0.08", "violin": "STRUM:0.35,0.65,0.85,0.12",
    "banjo": "STRUM:0.22,0.75,0.9,0.08", "harp": "GLISS",
    "musical keyboard": "KEYS", "accordion": "GLISS",
    "drum": "DRUM", "long drum": "DRUM", "maracas": "MARACAS",
}
EFFECT_EXACT.update(EFFECT_MUSIC)

# Where a keyword or subgroup default would miss the point of the emoji.
EFFECT_FIXES = {
    # Prohibition signs: the red sign flares, like a muted speaker.
    "prohibited": "MUTED", "no entry": "MUTED", "no bicycles": "MUTED", "no littering": "MUTED",
    "non-potable water": "MUTED", "no pedestrians": "MUTED", "no mobile phones": "MUTED",
    "no one under eighteen": "MUTED", "no smoking": "MUTED", "mobile phone off": "MUTED",
    # Keyword misfires.
    "tear-off calendar": "SHINE", "clockwise vertical arrows": "SHINE",
    "counterclockwise arrows button": "SHINE", "screwdriver": "SHINE",
    "water buffalo": "BLINK", "watermelon": "SHINE", "bullseye": "SPARKLE", "oil drum": "SHINE", "lungs": "HEARTBEAT",
    "wavy dash": "SHINE", "bell pepper": "SHINE", "water closet": "SHINE",
    "magnifying glass tilted left": "SHINE", "magnifying glass tilted right": "SHINE",
    "barber pole": "SHINE", "cyclone": "MAGIC", "umbrella": "RAIN", "closed umbrella": "SHINE",
    # Food that isn't hot.
    "cheese wedge": "SHINE", "butter": "SHINE", "salt": "SHINE", "canned food": "SHINE",
    "sushi": "SHINE", "rice ball": "SHINE", "rice cracker": "SHINE", "green salad": "SHINE",
    "sandwich": "SHINE", "egg": "SHINE", "popcorn": "CONFETTI", "fortune cookie": "SPARKLE",
    "moon cake": "GLOW", "dango": "SHINE", "oden": "STEAM", "bento box": "SHINE",
    # Vehicles without an engine don't smoke; signs and cable cars don't either.
    "bicycle": "SHINE", "kick scooter": "SHINE", "skateboard": "SHINE", "roller skate": "SHINE",
    "manual wheelchair": "SHINE", "wheel": "SHINE", "bus stop": "SHINE", "motorway": "SHINE",
    "railway track": "SHINE", "fuel pump": "DRIP", "stop sign": "BEACON",
    "construction": "BEACON", "parachute": "SWAY", "seat": "SHINE",
    "suspension railway": "SWAY", "mountain cableway": "SWAY", "aerial tramway": "SWAY",
    "wind chime": "SWAY", "horizontal traffic light": "BEACON",
    "vertical traffic light": "BEACON",
    # Phones ring; science bubbles; stars through a telescope.
    "telephone": "BELL:0.5,0.5", "telephone receiver": "BELL:0.5,0.5",
    "computer mouse": "SHINE", "computer disk": "SHINE", "test tube": "BUBBLES",
    "alembic": "BUBBLES", "petri dish": "BUBBLES", "telescope": "TWINKLE",
    "microscope": "SHINE", "syringe": "DRIP", "drop of blood": "DRIP", "pill": "SHINE",
    "adhesive bandage": "SHINE", "crutch": "SHINE", "x-ray": "GLOW",
    # People: firefighters blink like people; Santa brings snow; mages do magic.
    "firefighter": "BLINK", "man firefighter": "BLINK", "woman firefighter": "BLINK",
    "santa claus": "SNOW", "mrs. claus": "SNOW", "mx claus": "SNOW",
    "mage": "MAGIC", "man mage": "MAGIC", "woman mage": "MAGIC", "fairy": "MAGIC",
    "man fairy": "MAGIC", "woman fairy": "MAGIC", "genie": "MAGIC", "man genie": "MAGIC",
    "woman genie": "MAGIC", "merperson": "BUBBLES", "merman": "BUBBLES",
    "mermaid": "BUBBLES",
    # Folders swing open and shut.
    "file folder": "FOLDER:0.45", "open file folder": "FOLDER:-0.4", "grinning cat with smiling eyes": "BLINK",
}
EFFECT_EXACT.update(EFFECT_FIXES)

# Name keywords, tried in order when there is no exact entry.
EFFECT_RULES = [
    (r"\bflag\b", "FLAG"),
    (r"sweat", "SWEAT"),
    (r"tear|crying|\bcry", "TEARS"),
    (r"heart-eyes|heart eyes|kiss", "HEARTS"),
    (r"sleep|zzz", "SLEEPY"),
    (r"angry|pouting|\brage", "ANGRY"),
    (r"\bheart\b", "HEARTBEAT"),
    (r"television|monitor|screen", "STATIC"),
    (r"computer|laptop|\bphone\b|tablet", "SCREEN"),
    (r"\bfire|flame|torch|candle", "FLAME"),
    (r"\bstar\b|sparkl", "TWINKLE"),
    (r"lightning|voltage|electric", "ZAP"),
    (r"\brain\b|shower", "RAIN"),
    (r"snow|frost|freez", "SNOW"),
    (r"steam|hot |coffee|\btea\b", "STEAM"),
    (r"\bmusic|\bsong\b", "NOTES"),
    (r"\bwave\b|water|sea\b|ocean|lake|river", "RIPPLE"),
    (r"clock|o.clock|thirty", "CLOCK"),
    (r"siren|alarm|police|ambulance|fire engine", "BEACON"),
    (r"\bfish|bubble", "BUBBLES"),
    (r"ghost|spirit|zombie|vampire|mage|genie|fairy", "GHOSTLY"),
    (r"robot|\bcomputer|keyboard|\bdisk\b|floppy|dvd|input|\bid\b|\batm\b", "DIGITAL"),
    (r"celebrat|party|tada", "CONFETTI"),
    (r"firework", "FIREWORKS"),
    (r"smok|cloud|wind\b|fog", "SMOKE"),
    (r"building|house|castle|city|tower|stadium|apartment|skyscraper", "WINDOWS"),
    (r"diamond|gem\b|crystal|jewel|medal|trophy", "SPARKLE"),
    (r"\bmagic|wizard|witch|\bmage", "MAGIC"),
    (r"bulb|\blamp|lantern|light\b", "GLOW"),
    # Faces blink whatever their eyes look like; only eyes on their own look around.
    (r"\bface\b", "BLINK"),
    (r"eye\b|eyes\b", "LOOK"),
]

EFFECT_SUBGROUP = {
    "face-smiling": "BLINK", "face-affection": "HEARTS", "face-tongue": "BLINK",
    "face-hand": "BLINK", "face-neutral-skeptical": "BLINK", "face-sleepy": "SLEEPY",
    "face-unwell": "BLINK", "face-hat": "BLINK", "face-glasses": "SHINE",
    "face-concerned": "BLINK", "face-negative": "ANGRY", "face-costume": "GHOSTLY",
    "cat-face": "BLINK", "monkey-face": "BLINK", "heart": "HEARTBEAT", "emotion": "SPARKLE",
    "hand-fingers-open": "SHINE", "hand-fingers-partial": "SHINE",
    "hand-single-finger": "SHINE", "hand-fingers-closed": "SHINE", "hands": "SPARKLE",
    "hand-prop": "SHINE", "body-parts": "SHINE", "person": "BLINK", "person-gesture": "BLINK",
    "person-role": "BLINK", "person-fantasy": "MAGIC", "person-activity": "SHINE",
    "person-sport": "SHINE", "person-resting": "SLEEPY", "family": "HEARTS",
    "person-symbol": "SHINE", "skin-tone": "SHINE", "hair-style": "SHINE",
    "animal-mammal": "BLINK", "animal-bird": "BLINK", "animal-amphibian": "BLINK",
    "animal-reptile": "BLINK", "animal-marine": "BUBBLES", "animal-bug": "SWAY",
    "plant-flower": "SWAY", "plant-other": "SWAY", "food-fruit": "SHINE",
    "food-vegetable": "SHINE", "food-prepared": "STEAM", "food-asian": "STEAM",
    "food-sweet": "SPARKLE", "drink": "BUBBLES", "dishware": "SHINE", "place-map": "RIPPLE",
    "place-geographic": "TWINKLE", "place-building": "WINDOWS", "place-religious": "GLOW",
    "place-other": "TWINKLE", "transport-ground": "SMOKE", "transport-water": "RIPPLE",
    "transport-air": "SMOKE", "hotel": "SHINE", "time": "CLOCK", "sky-weather": "GLOW",
    "event": "CONFETTI", "award-medal": "SPARKLE", "sport": "SHINE", "game": "SPARKLE",
    "arts-crafts": "SPARKLE", "clothing": "SHINE", "sound": "SOUND", "music": "NOTES",
    "musical-instrument": "NOTES", "phone": "SCREEN", "computer": "DIGITAL",
    "light-video": "GLOW", "book-paper": "SHINE", "money": "SHINE", "mail": "SHINE",
    "writing": "SHINE", "office": "SHINE", "lock": "SHINE", "tool": "SHINE",
    "science": "ZAP", "medical": "HEARTBEAT", "household": "SHINE", "other-object": "SHINE",
    "transport-sign": "GLOW", "warning": "BEACON", "arrow": "SHINE", "religion": "GLOW",
    "zodiac": "TWINKLE", "av-symbol": "DIGITAL", "gender": "SHINE", "math": "DIGITAL",
    "punctuation": "BEACON", "currency": "SHINE", "other-symbol": "SHINE", "keycap": "DIGITAL",
    "alphanum": "DIGITAL", "geometric": "GLOW", "flag": "FLAG", "country-flag": "FLAG",
    "subdivision-flag": "FLAG",
}

# Picker tabs, in display order (emojibase group ids); "component" is not pickable.
PICKER_GROUPS = [0, 1, 3, 4, 5, 6, 7, 8, 9]


def motion_names(repo):
    src = open(os.path.join(repo, "app/src/main/java/com/dressence/nothinglauncherplus/anim/"
                                  "Motion.java"), encoding="utf-8").read()
    return set(re.findall(r"^    ([A-Z_]+)\(\"", src, re.M))


def effect_names(repo):
    src = open(os.path.join(repo, "app/src/main/java/com/dressence/nothinglauncherplus/anim/"
                                  "DotEffect.java"), encoding="utf-8").read()
    return set(re.findall(r"^    ([A-Z_]+) \{", src, re.M))


def pick(label, subgroup, exact, rules, defaults):
    if label in exact:
        return exact[label], "exact"
    found = next((m for p, m in rules if p.search(label)), None)
    if found is not None:
        return found, "rule"
    return defaults[subgroup], "subgroup"


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
    effects = effect_names(repo)
    effect_rules = [(re.compile(p), m) for p, m in EFFECT_RULES]
    unknown = {m.split(":")[0] for m in list(EFFECT_EXACT.values())
               + [m for _, m in EFFECT_RULES] + list(EFFECT_SUBGROUP.values())} - effects
    if unknown:
        sys.exit("unknown effects: %s" % sorted(unknown))

    lines = []
    stats = {"exact": 0, "rule": 0, "subgroup": 0}
    for e in sorted(data, key=lambda e: e.get("order", 1 << 30)):
        group = e.get("group")
        if group not in PICKER_GROUPS:
            continue
        label = e["label"].lower()
        subgroup = subgroups[str(e["subgroup"])]
        motion, how = pick(label, subgroup, EXACT, rules, SUBGROUP_DEFAULT)
        effect, _ = pick(label, subgroup, EFFECT_EXACT, effect_rules, EFFECT_SUBGROUP)
        stats[how] += 1
        lines.append("%s\t%d\t%s\t%s\t%s" % (e["emoji"], PICKER_GROUPS.index(group), motion,
                                             effect, e["label"]))

    out = os.path.join(repo, "app/src/main/assets/emoji_motions.tsv")
    with open(out, "w", encoding="utf-8") as f:
        f.write("# Generated by tools/gen_emoji_motions.py from emojibase-data (MIT).\n")
        f.write("# emoji<TAB>picker group<TAB>motion<TAB>effect<TAB>name\n")
        f.write("\n".join(lines) + "\n")
    used_motions = {l.split("\t")[2] for l in lines}
    print("emojis=%d %s motions used=%d/%d" % (len(lines), stats, len(used_motions),
                                               len(known) - 1))
    print("never used:", sorted(known - used_motions - {"NONE"}))
    used_effects = {}
    for l in lines:
        key = l.split("\t")[3].split(":")[0]
        used_effects[key] = used_effects.get(key, 0) + 1
    print("effects:", sorted(used_effects.items(), key=lambda kv: -kv[1]))
    print("effects never used:", sorted(effects - set(used_effects) - {"NONE"}))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
