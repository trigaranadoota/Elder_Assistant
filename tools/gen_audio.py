#!/usr/bin/env python3
"""Generate canned voice lines for the guide app in Telugu, Hindi and English.

Reads the Sarvam API key from <project>/secrets.properties, synthesizes each
line with the same params the app uses (bulbul:v3, priya, pace 0.85), and
writes WAVs into app/src/main/assets/voice/<lang>/<key>.wav.

Keep LINES in sync with Scripts.kt keys.
"""
import base64
import json
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "voice")
TTS_URL = "https://api.sarvam.ai/text-to-speech"

LANGS = {"te": "te-IN", "hi": "hi-IN", "en": "en-IN"}

# key -> {lang: text}
LINES = {
    "ask_what": {
        "te": "మీకు ఏమి కావాలి? ఫోటో పంపాలా, కాల్ చేయాలా, వీడియో చూడాలా?",
        "hi": "आपको क्या चाहिए? फोटो भेजना है, कॉल करना है, या वीडियो देखना है?",
        "en": "What do you need? Send a photo, make a call, or watch a video?",
    },
    "consent": {
        "te": "స్క్రీన్ చూడటానికి ఒక అనుమతి కావాలి. తెర తెరిచింది, సరే నొక్కండి.",
        "hi": "स्क्रीन देखने के लिए अनुमति चाहिए। स्क्रीन खुल गई है, ठीक है दबाएँ।",
        "en": "I need permission to see your screen. When the popup opens, tap OK.",
    },
    "couldnt_understand": {
        "te": "క్షమించండి, అర్థం కాలేదు. మళ్ళీ చెప్పండి. ఫోటో పంపాలా, కాల్ చేయాలా?",
        "hi": "माफ़ करें, समझ नहीं आया। फिर से बोलिए। फोटो भेजना है या कॉल करना है?",
        "en": "Sorry, I did not understand. Please say it again. Send a photo or make a call?",
    },
    "risk": {
        "te": "క్షమించండి, ఇది చాలా ముఖ్యం. డబ్బు లేదా అనుమతి అడిగితే ముందుకు వెళ్లవద్దు, నన్ను పిలవండి.",
        "hi": "माफ़ करें, यह बहुत ज़रूरी है। पैसे या अनुमति माँगे तो आगे न बढ़ें, मुझे बुलाएँ।",
        "en": "Sorry, this is important. If it asks for money or permission, do not continue. Call me.",
    },
    "screen_issue": {
        "te": "క్షమించండి, నేను తెర కొంచెం సరిగ్గా చూడట్లేదు. ఒక్కసారి తెర వైపు చూసి మళ్ళీ ప్రయత్నిస్తా.",
        "hi": "माफ़ करें, स्क्रीन ठीक से नहीं दिख रही। एक बार स्क्रीन देखकर फिर कोशिश करता हूँ।",
        "en": "Sorry, I cannot see the screen well. Let me look again and try once more.",
    },
    "recovery": {
        "te": "అయ్యో, మీరు వేరే స్క్రీన్ మీద ఉన్నారు. ఫర్వాలేదు. ముందు చెప్పిన అప్లికేషన్ తెరవండి, తర్వాత మళ్ళీ చెప్తా.",
        "hi": "अरे, आप किसी और स्क्रीन पर हैं। कोई बात नहीं। पहले बताया ऐप खोलिए, फिर बताता हूँ।",
        "en": "Oh, you are on a different screen. No problem. Open the app I told you, then I will guide again.",
    },
    "photo_open": {
        "te": "వాట్సాప్ తెరవాలి. హోమ్ స్క్రీన్లో పచ్చ రంగు గుండ్రని చిహ్నం లోపల తెల్ల ఫోన్ గుర్తు ఉంటుంది. దాన్ని నొక్కండి.",
        "hi": "व्हाट्सएप खोलना है। होम स्क्रीन पर हरे रंग का गोल निशान है, उसके अंदर सफ़ेद फोन है। उसे दबाएँ।",
        "en": "Open WhatsApp. On the home screen, tap the green round icon with the white phone inside.",
    },
    "photo_select": {
        "te": "మీ కుటుంబ సభ్యుల పేరు కనిపిస్తుంది. వాళ్ళ పేరు మీద నొక్కండి.",
        "hi": "आपके परिवार का नाम दिखेगा। उनके नाम पर दबाएँ।",
        "en": "You will see your family member's name. Tap their name.",
    },
    "photo_attach": {
        "te": "చాట్ తెరిచింది. కింద మెసేజ్ పెట్టె పక్కన పిన్ (పేపర్ క్లిప్) గుర్తు ఉంటుంది. దాన్ని నొక్కండి.",
        "hi": "चैट खुल गई। नीचे मैसेज बॉक्स के पास पिन (पेपरक्लिप) का निशान है। उसे दबाएँ।",
        "en": "The chat is open. Next to the message box at the bottom, tap the pin (paperclip) icon.",
    },
    "photo_gallery": {
        "te": "ఇప్పుడు ఆప్షన్ల జాబితా వస్తుంది. అందులో గ్యాలరీ నొక్కండి.",
        "hi": "अब विकल्पों की सूची आएगी। उसमें गैलरी दबाएँ।",
        "en": "Now a list of options appears. Tap Gallery.",
    },
    "photo_pick": {
        "te": "గ్యాలరీ ఫోటోలు తెరిచింది. మీరు పంపాలనుకున్న ఫోటో నొక్కండి.",
        "hi": "गैलरी की फोटो खुल गईं। जो फोटो भेजनी है उसे दबाएँ।",
        "en": "The gallery is open. Tap the photo you want to send.",
    },
    "photo_send": {
        "te": "ఫోటో ఎంపిక అయింది. క్రింద కుడివైపు పచ్చ రంగు పంపు బటన్ నొక్కండి.",
        "hi": "फोटो चुन ली गई। नीचे दाईं ओर हरे रंग का भेजें बटन दबाएँ।",
        "en": "The photo is selected. Tap the green Send button at the bottom right.",
    },
    "photo_done": {
        "te": "అయిపోయింది! ఫోటో విజయవంతంగా పంపబడింది. మీరు బాగా చేశారు.",
        "hi": "हो गया! फोटो भेज दी गई। आपने बहुत अच्छा किया।",
        "en": "Done! The photo was sent. You did great.",
    },
    "call_open": {
        "te": "వాట్సాప్ తెరవాలి. హోమ్ స్క్రీన్లో పచ్చ రంగు గుండ్రని చిహ్నం లోపల తెల్ల ఫోన్ గుర్తు ఉంటుంది. దాన్ని నొక్కండి.",
        "hi": "व्हाट्सएप खोलना है। होम स्क्रीन पर हरे रंग का गोल निशान है, उसके अंदर सफ़ेद फोन है। उसे दबाएँ।",
        "en": "Open WhatsApp. On the home screen, tap the green round icon with the white phone inside.",
    },
    "call_select": {
        "te": "మీ కుటుంబ సభ్యుల పేరు కనిపిస్తుంది. వాళ్ళ పేరు మీద నొక్కండి.",
        "hi": "आपके परिवार का नाम दिखेगा। उनके नाम पर दबाएँ।",
        "en": "You will see your family member's name. Tap their name.",
    },
    "call_videocall": {
        "te": "చాట్ పేజీ తెరిచింది. పైన ఒక చిన్న వీడియో కెమెరా గుర్తు ఉంది. దాన్ని నొక్కండి.",
        "hi": "चैट पेज खुल गया। ऊपर छोटा वीडियो कैमरा निशान है। उसे दबाएँ।",
        "en": "The chat is open. At the top there is a small video camera icon. Tap it.",
    },
    "call_voicecall": {
        "te": "చాట్ పేజీ తెరిచింది. పైన ఒక చిన్న ఫోన్ గుర్తు ఉంది. దాన్ని నొక్కండి.",
        "hi": "चैट पेज खुल गया। ऊपर छोटा फोन निशान है। उसे दबाएँ।",
        "en": "The chat is open. At the top there is a small phone icon. Tap it.",
    },
    "call_wait": {
        "te": "కాల్ వెళ్తోంది. కనెక్ట్ అయ్యే వరకు కొంచెం వేచి ఉండండి.",
        "hi": "कॉल जा रही है। कनेक्ट होने तक थोड़ा इंतज़ार करें।",
        "en": "The call is going through. Please wait a moment until it connects.",
    },
    "call_done": {
        "te": "వీడియో కాల్ ప్రారంభమైంది. మీ కుటుంబ సభ్యుడు కనిపిస్తారు. ఆనందించండి.",
        "hi": "वीडियो कॉल शुरू हो गई। आपके परिवार वाले दिखेंगे। आनंद लें।",
        "en": "The video call has started. You will see your family member. Enjoy.",
    },
    "call_done_voice": {
        "te": "కాల్ ప్రారంభమైంది. మీ కుటుంబ సభ్యులతో మాట్లాడండి.",
        "hi": "कॉल शुरू हो गई। अपने परिवार वालों से बात करें।",
        "en": "The call has started. Talk to your family member.",
    },
    "play_open": {
        "te": "యూట్యూబ్ తెరవాలి. హోమ్ స్క్రీన్లో ఎరుపు రంగు చతురస్రం లోపల తెల్ల ప్లే గుర్తు ఉంటుంది. దాన్ని నొక్కండి.",
        "hi": "यूट्यूब खोलना है। होम स्क्रीन पर लाल रंग का चौकोर निशान है, उसके अंदर सफ़ेद प्ले है। उसे दबाएँ।",
        "en": "Open YouTube. On the home screen, tap the red square icon with the white play symbol inside.",
    },
    "play_search": {
        "te": "యూట్యూబ్ తెరిచింది. పైన ఉన్న సెర్చ్ (భూతద్దం) గుర్తు నొక్కండి.",
        "hi": "यूट्यूब खुल गया। ऊपर सर्च (मैग्निफ़ाइंग ग्लास) का निशान दबाएँ।",
        "en": "YouTube is open. Tap the search (magnifying glass) icon at the top.",
    },
    "play_enjoy": {
        "te": "మీకు నచ్చిన వీడియో నొక్కండి, అది ప్లే అవుతుంది.",
        "hi": "जो वीडियो पसंद है उसे दबाएँ, वह चल जाएगा।",
        "en": "Tap a video you like, and it will start playing.",
    },
    "play_done": {
        "te": "పని పూర్తయింది! ఇప్పుడు మీకు కావాల్సిన వీడియో సెర్చ్ చేయండి. ఆనందించండి.",
        "hi": "काम पूरा हुआ! अब जो वीडियो चाहिए उसे सर्च करें। आनंद लें।",
        "en": "Done! Now search for what you want. Enjoy.",
    },
    "medicine": {
        "te": "మందు తీసుకునే సమయం అయింది.",
        "hi": "दवा लेने का समय हो गया है।",
        "en": "Time to take medicine.",
    },
    "intro_photo": {
        "te": "మీకు ఫోటో పంపాలి. అందుకోసం వాట్సాప్ తెరవాలి.",
        "hi": "आपको फोटो भेजनी है। इसके लिए व्हाट्सएप खोलना होगा।",
        "en": "You want to send a photo. For that we need WhatsApp.",
    },
    "intro_call": {
        "te": "వీడియో కాల్ చేయాలి. అందుకోసం వాట్సాప్ తెరవాలి.",
        "hi": "वीडियो कॉल करनी है। इसके लिए व्हाट्सएप खोलना होगा।",
        "en": "You want to make a video call. For that we need WhatsApp.",
    },
    "intro_voice": {
        "te": "సాధారణ కాల్ చేయాలి. అందుకోసం వాట్సాప్ తెరవాలి.",
        "hi": "नॉर्मल कॉल करनी है। इसके लिए व्हाट्सएप खोलना होगा।",
        "en": "You want to make a normal call. For that we need WhatsApp.",
    },
    "ask_call_kind": {
        "te": "వీడియో కాల్ చేయాలా, సాధారణ కాల్ చేయాలా?",
        "hi": "वीडियो कॉल करनी है या नॉर्मल कॉल?",
        "en": "Do you want a video call or a normal call?",
    },
    "intro_play": {
        "te": "వీడియో చూడాలి. అందుకోసం యూట్యూబ్ తెరవాలి.",
        "hi": "वीडियो देखना है। इसके लिए यूट्यूब खोलना होगा।",
        "en": "You want to watch a video. For that we need YouTube.",
    },
    "go_home": {
        "te": "మొదట హోమ్ స్క్రీన్కి వెళ్లండి. స్క్రీన్ కింది భాగం నుండి పైకి స్వైప్ చేయండి. తరువాత నేను మీకు చెబుతాను.",
        "hi": "पहले होम स्क्रीन पर जाएँ। स्क्रीन के नीचे से ऊपर स्वाइप करें। फिर मैं आपको बताऊँगा।",
        "en": "First, go to the home screen. Swipe up from the bottom of the screen. Then I will guide you.",
    },
    "offline_airplane": {
        "te": "ఏర్ప్లేన్ మోడ్ ఆన్ ఉంది. దయచేసి ఆఫ్ చేయండి.",
        "hi": "एयरप्लेन मोड चालू है। कृपया बंद करें।",
        "en": "Airplane mode is on. Please turn it off.",
    },
    "offline_no_internet": {
        "te": "ఇంటర్నెట్ లేదు. దయచేసి ఇంటర్నెట్ ఆన్ చేయండి.",
        "hi": "इंटरनेट नहीं है। कृपया इंटरनेट चालू करें।",
        "en": "There is no internet. Please turn on the internet.",
    },
    "offline_provider_down": {
        "te": "సేవ ప్రస్తుతం అందుబాటులో లేదు. కొంచెం సేపు తర్వాత ప్రయత్నించండి.",
        "hi": "सेवा अभी उपलब्ध नहीं है। थोड़ी देर बाद कोशिश करें।",
        "en": "The service is unavailable right now. Please try again in a little while.",
    },
}


def api_key():
    props = os.path.join(ROOT, "secrets.properties")
    if not os.path.exists(props):
        sys.exit("secrets.properties not found")
    with open(props, encoding="utf-8-sig") as f:
        for line in f:
            if line.startswith("SARVAM_API_KEY="):
                return line.split("=", 1)[1].strip()
    sys.exit("SARVAM_API_KEY missing in secrets.properties")


def synthesize(text, lang, key, key_id):
    body = json.dumps({
        "inputs": [text],
        "target_language_code": LANGS[lang],
        "model": "bulbul:v3",
        "speaker": "priya",
        "pace": 0.85,
        "temperature": 0.7,
        "speech_sample_rate": 24000,
    }).encode()
    req = urllib.request.Request(
        TTS_URL,
        data=body,
        headers={"api-subscription-key": key_id, "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = json.loads(resp.read().decode())
    audios = data.get("audios", [])
    if not audios:
        sys.exit(f"no audios for {lang}/{key}: {data}")
    a = audios[0]
    b64 = a.get("audio_base64", a) if isinstance(a, dict) else a
    wav = base64.b64decode(b64)
    out_dir = os.path.join(ASSETS, lang)
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, key + ".wav")
    with open(path, "wb") as f:
        f.write(wav)
    print(f"{lang}/{key}: {len(wav)} bytes")


if __name__ == "__main__":
    k = api_key()
    only = set(sys.argv[1:])
    for lang in LANGS:
        for key, texts in LINES.items():
            if only and key not in only:
                continue
            synthesize(texts[lang], lang, key, k)
    print("done")
