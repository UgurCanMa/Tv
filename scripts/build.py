#!/usr/bin/env python3
"""Baut tv.m3u: kostenlose Live-Sender (DE/TR) plus Filme, Krimis, Serien und Dokus
aus den öffentlich-rechtlichen Mediatheken.

Quellen
- Live-Sender: iptv-org (github.com/iptv-org/iptv) und Kodinerds (github.com/jnk22/kodinerds-iptv)
- Filme & Co.: MediathekView-Filmliste (ARD, ZDF, arte, 3sat, Dritte, KiKA ...),
  Ersatzweise die MediathekViewWeb-API.

Schlägt eine Quelle fehl, bleibt der alte Teil der Liste erhalten.
"""
import collections
import datetime as dt
import json
import lzma
import os
import re
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'tv.m3u')
STATS = os.path.join(ROOT, 'stats.txt')
DEBUG_FILE = os.path.join(ROOT, 'debug_themen.txt')
DEBUG = os.environ.get('DEBUG') == '1'
UA = 'Mozilla/5.0 (compatible; UgurCanMa-Tv/1.0; +https://github.com/UgurCanMa/Tv)'
NOW = time.time()
LOG = []


def log(msg):
    print(msg, flush=True)
    LOG.append(msg)


def fetch(url, timeout=90, tries=3, data=None, headers=None):
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, data=data, headers={'User-Agent': UA, **(headers or {})})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(2 * (i + 1))
    raise RuntimeError(f'{url}: {last}')


# ---------------------------------------------------------------- M3U lesen

def split_extinf(line):
    inq = False
    for i, ch in enumerate(line):
        if ch == '"':
            inq = not inq
        elif ch == ',' and not inq:
            return line[:i], line[i + 1:]
    return line, ''


def parse_m3u(text):
    out, cur, opts = [], None, []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith('#EXTINF'):
            cur, opts = line, []
        elif line.startswith(('#EXTVLCOPT', '#KODIPROP', '#EXTHTTP')):
            opts.append(line)
        elif line and not line.startswith('#') and cur:
            head, name = split_extinf(cur)
            attrs = dict(re.findall(r'([\w-]+)="([^"]*)"', head))
            out.append(dict(attrs=attrs, name=name.strip(), url=line, opts=opts))
            cur, opts = None, []
    return out


def host(u):
    return (urllib.parse.urlparse(u).hostname or '').lower()


def is_ip(h):
    return re.fullmatch(r'[\d.]+', h or '') is not None


def hmatch(h, suffixes):
    return any(h == s or h.endswith('.' + s) or h.endswith(s) for s in suffixes)


def labels(n):
    return re.findall(r'\[([^\]]+)\]', n)


def clean(n):
    n = re.sub(r'\s*\[[^\]]*\]', '', n)
    n = re.sub(r'\s*\((?:\d{3,4}p|Germany|Turkiye|United States|United Kingdom)\)', '', n)
    n = re.sub(r'\s*\(\d{3,4}p\)', '', n)
    return re.sub(r'\s+', ' ', n).strip()


def norm(n):
    n = clean(n).lower().replace('ß', 'ss')
    n = re.sub(r'\b(hd|tv|fernsehen|television)\b', '', n)
    return re.sub(r'[^a-z0-9]', '', n)


# ---------------------------------------------------------------- Gruppen

ARD = 'DE | ARD & ZDF'; FIL = 'DE | Film-Sender'; SER = 'DE | Serien-Sender'; NEW = 'DE | Nachrichten & Doku'
SPO = 'DE | Sport'; KID = 'DE | Kinder'; MUS = 'DE | Musik'; REG = 'DE | Regional'; REL = 'DE | Religion'
T_MAIN = 'TR | Hauptsender'; T_NEW = 'TR | Nachrichten'; T_SPO = 'TR | Sport'; T_KID = 'TR | Kinder'
T_MUS = 'TR | Musik'; T_DOK = 'TR | Doku'; T_REL = 'TR | Religion'; T_REG = 'TR | Regional'
ENF = 'EN | Film-Sender (Englisch)'
LIVE_TOP = [ARD, FIL, SER, NEW, SPO, KID, MUS, T_MAIN, T_NEW, T_SPO, T_KID, T_MUS, T_DOK, ENF]
LIVE_BOTTOM = [REG, T_REG, REL, T_REL]
VOD_PREFIXES = ('FILME |', 'KRIMIS |', 'SERIEN |', 'DOKUS |', 'KINDER |')

IPTV_ORG = 'https://raw.githubusercontent.com/iptv-org/iptv/gh-pages/'
KODI = 'https://raw.githubusercontent.com/jnk22/kodinerds-iptv/master/iptv/clean/'


# ---------------------------------------------------------------- Live-Sender

def build_live(sources):
    entries, seen_urls, seen_names = [], set(), set()
    fix_group = {'WDR 1 Live': MUS, 'Salve TV': REL, 'Ilke TV': T_REL, 'Sercem TV': T_REL}
    rename = {
        'Frasier: Beruhmte Gaste': 'Frasier: Berühmte Gäste', 'McLeods Tochter': 'McLeods Töchter',
        'TV38 Sudost-Niedersachen': 'TV38 Südost-Niedersachsen', 'Love The Planet DE': 'Love The Planet', 'DF1 HD': 'DF1',
        'one HD': 'ONE', 'WDR 1 Live': '1LIVE', 'Halk TV SD': 'Halk TV', 'A Spor SD': 'A Spor', 'TRT Cocuk': 'TRT Çocuk',
        'TRT Diyanet Cocuk': 'TRT Diyanet Çocuk', 'Minika Cocuk': 'Minika Çocuk', 'TRT Muzik': 'TRT Müzik',
        'TRT Turk': 'TRT Türk', 'Dream Turk': 'Dream Türk', 'Number 1 Ask': 'Number 1 Aşk',
        'PowerTurk Akustik': 'PowerTürk Akustik', 'PowerTurk Slow': 'PowerTürk Slow', 'PowerTurk Taptaze': 'PowerTürk Taptaze',
        'Lalegul TV': 'Lalegül TV', 'Guneydogu TV': 'Güneydoğu TV', 'Cay TV': 'Çay TV', 'Cekmekoy TV': 'Çekmeköy TV',
        'Icel TV': 'İçel TV', 'Ilke TV': 'İlke TV', 'TRT EBA Ilkokul': 'TRT EBA İlkokul', 'Ekoturk': 'Ekotürk',
        'TurkHaber TV': 'TürkHaber TV', 'MTurk TV': 'MTürk TV', 'Spacetoon Turkey': 'Spacetoon Türkiye',
        'Sercem TV': 'Serçem TV', 'Euronews German': 'Euronews (Deutsch)'}

    def add(group, x, name=None):
        if x['url'] in seen_urls:
            return
        nm = name or clean(x['name'])
        group = fix_group.get(nm, group)
        nm = rename.get(nm, nm)
        key = (group.split(' | ')[0], norm(nm))
        if key in seen_names:
            return
        seen_urls.add(x['url']); seen_names.add(key)
        entries.append(dict(group=group, name=nm, logo=x['attrs'].get('tvg-logo', ''),
                            tvgid=x['attrs'].get('tvg-id', ''), url=x['url'], opts=x['opts']))

    # 1) Kodinerds: offizielle deutsche Streams
    k_local = {x['url'] for x in sources['kodi_local']}
    kmap = [
        (r'^(Das Erste HD|ZDF HD|ZDFneo HD|ZDFinfo HD|3sat|ARTE HD|phoenix|tagesschau24|one HD|ARD-alpha)$', ARD),
        (r'^(WDR HD|SWR (Baden-Württemberg|Rheinland-Pfalz) HD|NDR (Niedersachsen|Schleswig-Holstein|Mecklenburg-Vorpommern|Hamburg) HD'
         r'|Radio Bremen TV HD|BR Fernsehen (Nord|Süd) HD|MDR (Sachsen|Sachsen-Anhalt|Thüringen) HD|hr-fernsehen'
         r'|rbb (Berlin|Brandenburg) HD|SR Fernsehen HD)$', ARD),
        (r'^WDR (Aachen|Bonn|Bergisches Land|Dortmund|Düsseldorf|Duisburg|Köln|Münsterland|Ostwestfalen|Südwestfalen|Ruhr) HD$', REG),
        (r'^(Südthüringer Regionalfernsehen|Rennsteig\.tv|h1|Wir24TV)$', REG),
        (r'^(WELT|Bild|AUTO BILD TV|Welt der Wunder|Dokusat|Deutsche Welle \(EN\)|Parlamentsfernsehen 1)$', NEW),
        (r'^(Red Bull TV|More than Sports TV|DAZN FAST\+|SPORT BILD TV)$', SPO),
        (r'^(KiKA|Lilo\.TV|Nickelodeon)$', KID),
        (r'^(ANIXE HD)$', SER),
        (r'^(DF1 HD)$', FIL),
        (r'^(Ö3-VisualRadio|SWR3-VisualRadio|DASDING-VisualRadio|DELUXE MUSIC|DELUXE MUSIC DANCE|DELUXE MUSIC RAP|Schlager Deluxe'
         r'|Folx TV|One 1 Music TV|Zwei 2 Music TV|Deutsches Musik Fernsehen|Kronehit TV)$', MUS),
        (r'^(Bibel TV|Bibel TV Impuls|Bibel TV Musik|K-TV|EWTN katholisches TV|ERF 1)$', REL),
        (r'^(TRT World HD)$', T_NEW),
    ]
    for x in sources['kodi']:
        if 'youtube.com' in host(x['url']):
            continue
        nm = x['name'].strip()
        g = REG if x['url'] in k_local else next((g for rx, g in kmap if re.match(rx, nm)), None)
        if not g:
            continue
        nm = {'TRT World HD': 'TRT World (Englisch)', 'Deutsche Welle (EN)': 'DW (Englisch)'}.get(nm, nm)
        add(g, x, nm)

    # 2) iptv-org: weitere deutsche Sender (Pluto TV, Rakuten TV usw.)
    de_ok = ['jmp2.uk', 'amazonaws.com', 'wurl.com', 'wurl.tv', 'amagi.tv', 'cloudfront.net', 'iptv-playoutcenter.de',
             'streaming.magentamusik.de', 'creacast.com', 'wdrfs247.akamaized.net', 'lotus.stingray.com', 'playout.vju.tv',
             'cloudflarestream.com', 'video-stream-hosting.de', 'welocal.stream', 'welocal.world', 'streamlock.net',
             '3qsdn.com', 'wtnet.de', 'oksh.de', 'ok54.de', 'rosebud-media.de', 'siebnich.info', 'nrwision.de',
             'hamburg1.de', 'web12.mdn.ors.at', 'ma.anixa.tv']
    de_skip = (r'(SRF|RT DE|Cira|MTA|DW Russian|TVRUS|Kinomir|Ost West|MC TV|KaufBei|Juwelo|QVC|HSE|Handystar|Sonnenklar|1-2-3'
               r'|Adria|Folx|One Music|Zwei Music|Sophia|Neue Zeit|Neu Jerusalem|Light Channel|KIT-TV|iSTUFF|Sonus|Finest'
               r'|Unserding|Hitradio|Kronehit|BabyFirst|Graham Norton|Horse|NBC 1|Peer TV|EbS|Parlamentsfernsehen|Antenne'
               r'|RTL Zwee|Rai|ORF [12]|ORF III|ServusTV|Okto|Tele|Canal Alpha|Immaculata|Uppera|TNT|Meteonews|Radio 3i'
               r'|Comedy Central \(|MTV|^Nickelodeon \(|Disney|TELE 5|TLC|DMAX|ProSieben|kabel eins|Echt ?Jetzt'
               r'|Laughs in Spanish|Weihnachten im Sommer|Bibel TV$|^OK54$|^OK Trier$|^OK Weinstrasse Neustadt$|^Augsburg TV$'
               r'|Adult|XXX|Erotik|Sexy)')

    def de_group(cat, nm, h):
        c = cat.split(';')
        if 'Haialarm' in nm or 'Kult Comedies' in nm:
            return FIL
        if 'Star Trek' in nm:
            return SER
        if 'Movies' in c and 'Series' not in c:
            return FIL
        if set(c) & {'Series', 'Comedy', 'Animation', 'Entertainment'}:
            return SER
        if 'Kids' in c:
            return KID
        if 'Sports' in c:
            return SPO
        if 'Music' in c:
            return MUS
        if 'Religious' in c:
            return REL
        if set(c) & {'Documentary', 'Education', 'Lifestyle', 'Outdoor', 'Culture', 'News', 'Travel', 'Cooking'}:
            return NEW
        if hmatch(h, ['iptv-playoutcenter.de', 'welocal.stream', 'welocal.world', 'video-stream-hosting.de', 'streamlock.net',
                      'oksh.de', 'ok54.de', 'wtnet.de', 'rosebud-media.de', 'siebnich.info', 'nrwision.de', 'hamburg1.de']):
            return REG
        return None

    for x in sources['de'] + sources['deu']:
        tid = x['attrs'].get('tvg-id', ''); h = host(x['url']); nm = clean(x['name'])
        base = tid.split('@')[0]
        if not (base.endswith('.de') or re.search(r'@(Germany|DACH|German|DE)$', tid) or 'German' in base):
            continue
        if is_ip(h) or not hmatch(h, de_ok) or re.search(de_skip, nm):
            continue
        if h == 'ma.anixa.tv' and not nm.startswith('Anixe'):
            continue
        g = de_group(x['attrs'].get('group-title', ''), nm, h)
        if not g:
            continue
        nm = re.sub(r'\s+Germany$', '', nm)
        add(g, x, 'Red Bull TV (Deutsch)' if nm == 'Red Bull TV DE' else nm)

    # 3) iptv-org: türkische Sender (nur offizielle Quellen, nicht geo-gesperrt)
    tr_ok = ['medya.trt.com.tr', 'live.trt.com.tr', 'daioncdn.net', 'ercdn.net', 'mncdn.com', 'mediatriple.net',
             'powerapp.com.tr', 'tjk.org', 'tgrthaber.com', 'blutv.com', 'duhnet.tv', 'radyotvonline.net', 'ltnschedule.com',
             'spacetoongo.com', 'yoltv.com', 'tv42.com.tr', 'tvden.com.tr', 'edessatv.com', 'sercemtv.com.tr', 'ilketv.com.tr',
             'zaroktv.com.tr', 'ekoturk.net', 'yayin.com.tr', 'socialsmart.tv', 'taksimbilisim.com', 'netmedya.net',
             '4utv.live', '603c568fccdf5.streamlock.net', '59cba4d34b678.streamlock.net']
    tr_skip = (r'(beIN|Eurosport|S Sport|National Geographic|BBC|Viasat|Disney|MovieSmart|Drama|FB TV|Timeless|Elsharq'
               r'|Mekameleen|Al-|Imam|Almahriah|Kurdistan TV|West Azerbaijan|Manas|KN Music|Luys|Persiana|Qaf|Vav|Love Nature'
               r'|MyZen|BabyFirst|Stingray|Finest|MC TV|Arabi)')
    tmap = [
        (r'^(TRT1|ATV|KanalD|StarTV|TV8|Kanal7|BeyazTV|A2TV|TV4|360|EuroD|Kanal7Avrupa|YOLTV|TRTTurk|TRTAvaz|TRTKurdi)\.', T_MAIN),
        (r'^(TRTHaber|NTV|AHaber|HalkTV|TGRTHaber|24TV|Tele1|TVNET|CNBCe|BloombergHT|Ekoturk|FlashHaberTV|DHA|TBMMTV|TurkHaberTV)\.', T_NEW),
        (r'^(ASpor|HTSporTV|TJKTV|TJKTV2|TRT3)\.', T_SPO),
        (r'^(TRTCocuk|TRTDiyanetCocuk|MinikaCocuk|MinikaGo|SpacetoonTurkey|ZarokTV|TRTEBA|TRTEBAIlkokul|TRTEBALise)\.', T_KID),
        (r'^(TRTMuzik|KralPopTV|PowerTV|PowerDance|PowerLove|PowerTurkAkustik|PowerTurkSlow|PowerTurkTaptaze|Number1\w*|DreamTurk)\.', T_MUS),
        (r'^(TGRTBelgesel|GZT)\.', T_DOK),
        (r'^(DiyanetTV|SemerkandTV|LalegulTV|KanalHayat)\.', T_REL),
    ]
    for x in sources['tr'] + sources['tur']:
        tid = x['attrs'].get('tvg-id', ''); h = host(x['url']); nm = clean(x['name'])
        if 'Geo-blocked' in labels(x['name']) or is_ip(h) or not hmatch(h, tr_ok) or re.search(tr_skip, nm):
            continue
        g = next((g for rx, g in tmap if re.match(rx, tid)), None)
        if g is None:
            if not tid.split('@')[0].endswith('.tr'):
                continue
            g = T_REG
        add(g, x, nm)

    # 4) Englische Film-Sender (kostenlose FAST-Kanäle)
    en_keep = ['Pluto TV Movies GB', 'Pluto TV Cult Films', 'Pluto TV Family Movie Club', 'Pluto TV Fantasy & Horror',
               'Pluto TV Westerns', 'Pluto TV Crime Movies', 'Pluto TV Icons', 'Pluto TV Trending Now', 'Pluto TV Staff Picks',
               'Pluto TV Spotlight', 'Pluto TV Terror', 'Pluto TV Reaction', 'Pluto TV Franchise Favorites',
               'Paramount Movie Channel', 'Paramount+ Picks', '70s Cinema', '80s Rewind', '90s Throwback', '00s Replay',
               'Classic Movies Channel', 'Classic Movie Westerns', 'Flicks of Fury', 'BET Cinema', 'Kids Movie Club', 'Runtime',
               'The Film Detective', 'Made In Hollywood', 'ARTFLIX Movie Classics', 'MyTime Movie Network', 'Maverick Black Cinema']
    for x in sources['movies']:
        h = host(x['url'])
        nm = re.sub(r'\s*\((?:United Kingdom|Germany)\)', '', clean(x['name']))
        if not hmatch(h, ['jmp2.uk', 'wurl.tv', 'wurl.com', 'amagi.tv']) or nm not in en_keep:
            continue
        if nm == 'MyTime Movie Network' and 'uk' not in x['attrs'].get('tvg-id', '').lower():
            continue
        add(ENF, x, {'Pluto TV Movies GB': 'Pluto TV Movies (UK)', 'Pluto TV Westerns': 'Pluto TV Westerns (UK)'}.get(nm, nm))

    prio = {
        ARD: ['Das Erste HD', 'ZDF HD', 'ZDFneo HD', 'ZDFinfo HD', '3sat', 'ARTE HD', 'phoenix', 'tagesschau24', 'ONE', 'ARD-alpha',
              'SWR Baden-Württemberg HD', 'SWR Rheinland-Pfalz HD', 'BR Fernsehen Süd HD', 'BR Fernsehen Nord HD', 'hr-fernsehen',
              'MDR Sachsen HD', 'MDR Sachsen-Anhalt HD', 'MDR Thüringen HD', 'NDR Niedersachsen HD', 'NDR Hamburg HD',
              'NDR Schleswig-Holstein HD', 'NDR Mecklenburg-Vorpommern HD', 'Radio Bremen TV HD', 'rbb Berlin HD',
              'rbb Brandenburg HD', 'SR Fernsehen HD', 'WDR HD'],
        FIL: ['Pluto TV Movies', 'Rakuten TV Top Movies', 'Rakuten TV Action Movies', 'Rakuten TV Comedy Movies',
              'Rakuten TV Drama Movies', 'Rakuten TV Family Movies', 'Moviedome', 'DF1', 'Pluto TV Kultfilme', 'Pluto TV Kult Comedies'],
        NEW: ['WELT', 'Bild', 'Euronews (Deutsch)', 'N24 Doku', 'Welt der Wunder', 'Dokusat'],
        SPO: ['DAZN FAST+', 'Red Bull TV (Deutsch)', 'Red Bull TV', 'SPORT BILD TV', 'More than Sports TV'],
        KID: ['KiKA', 'Nickelodeon', 'Lilo.TV', 'RiC', 'SpongeBob Schwammkopf', 'Nick Jr. Club'],
        MUS: ['DELUXE MUSIC', 'DELUXE MUSIC DANCE', 'DELUXE MUSIC RAP', 'Schlager Deluxe'],
        T_MAIN: ['TRT 1', 'ATV', 'Kanal D', 'Star TV', 'TV8', 'Kanal 7', 'Beyaz TV', 'A2TV', 'TV 4', '360', 'Euro D',
                 'Kanal 7 Avrupa', 'TRT Türk'],
        T_NEW: ['TRT Haber', 'NTV', 'A Haber', 'Halk TV', 'TGRT Haber', '24 TV', 'Tele 1', 'TVNET', 'CNBC-e', 'Bloomberg HT'],
        T_SPO: ['A Spor', 'HTSpor TV', 'TRT 3'],
        T_KID: ['TRT Çocuk', 'Minika Çocuk', 'Minika Go', 'TRT Diyanet Çocuk', 'Spacetoon Türkiye'],
        T_MUS: ['TRT Müzik', 'Kral Pop TV', 'Power TV', 'PowerTürk Taptaze', 'Number 1 TV', 'Dream Türk'],
        REG: ['Rhein Neckar Fernsehen', 'OK Ludwigshafen', 'OK Weinstraße (Neustadt)', 'OK RheinLokal (Worms)',
              'Regio TV Stuttgart', 'Baden TV'],
    }
    order = LIVE_TOP + LIVE_BOTTOM

    def sk(e):
        p = prio.get(e['group'], [])
        return (order.index(e['group']), p.index(e['name']) if e['name'] in p else len(p), e['name'].lower())

    entries.sort(key=sk)
    return entries


# ---------------------------------------------------------------- Mediathek (Filme & Co.)

FILMLISTE_URLS = ['https://liste.mediathekview.de/Filmliste-akt.xz'] + \
    [f'https://verteiler{i}.mediathekview.de/Filmliste-akt.xz' for i in range(1, 7)]
SENDER_OK = {'ARD', 'ZDF', 'ARTE.DE', '3SAT', 'BR', 'HR', 'MDR', 'NDR', 'RBB', 'SR', 'SWR', 'WDR', 'RADIO BREMEN TV',
             'KIKA', 'ZDF-TIVI', 'PHOENIX', 'ONE', 'ARD-ALPHA', 'TAGESSCHAU24', 'ZDFNEO', 'ZDFINFO', 'FUNK.NET'}
KIDS_SENDER = {'KIKA', 'ZDF-TIVI'}
SENDER_LOGO_NAME = {'ARD': 'Das Erste HD', 'ZDF': 'ZDF HD', 'ARTE.DE': 'ARTE HD', '3SAT': '3sat', 'BR': 'BR Fernsehen Süd HD',
                    'HR': 'hr-fernsehen', 'MDR': 'MDR Sachsen HD', 'NDR': 'NDR Niedersachsen HD', 'RBB': 'rbb Berlin HD',
                    'SR': 'SR Fernsehen HD', 'SWR': 'SWR Baden-Württemberg HD', 'WDR': 'WDR HD',
                    'RADIO BREMEN TV': 'Radio Bremen TV HD', 'KIKA': 'KiKA', 'ZDF-TIVI': 'ZDF HD', 'PHOENIX': 'phoenix',
                    'ONE': 'one HD', 'ARD-ALPHA': 'ARD-alpha', 'TAGESSCHAU24': 'tagesschau24', 'ZDFNEO': 'ZDFneo HD',
                    'ZDFINFO': 'ZDFinfo HD'}

I = re.I
EXCL = re.compile(r'audiodeskription|hörfassung|hörfilm|gebärdensprach|\bDGS\b|klare sprache|leichte sprache|\(AD\)|\(OV\)|\bOmU\b'
                  r'|\((?:englisch|english|original|swe|eng|fra|fre|ita|dan|nor|fin|spa|org)\)|originalfassung|originalversion'
                  r'|englische fassung|\btrailer\b|\bteaser\b|vorschau|making[- ]of|outtakes|hinter den kulissen|\bclip\b'
                  r'|\(ausschnitt\)|\bbonus\b|\bshorts?\b', I)
NOFILM = re.compile(
    r'tagesschau|tagesthemen|\bheute\b|nachrichten|wetter|brisant|magazin|talk|lanz|illner|maischberger|hart aber fair|miosga'
    r'|presseclub|gottesdienst|\bmesse\b|bundestag|landtag|plenar|parlament|sportschau|sportstudio|\bsport|fußball|bundesliga'
    r'|handball|biathlon|olympi|paralymp|formel 1|re-live|relive|replay|spieltag|weltcup|\bwm\b|\bem\b|volleyball|eishockey'
    r'|tennis|sailgp|etappe|tour de|konzert|concert|festival|\blive\b|gala\b|verleihung|quiz|\bshow\b|fernsehgarten|kochen'
    r'|küche|rezept|backen|lecker|ratgeber|verbraucher|plusminus|wiso|frontal|monitor|kontraste|report mainz|panorama'
    r'|kabarett|satire|heute-show|extra 3|die anstalt|lesung|hörspiel|podcast|gespräch|interview|pressekonferenz|debatte'
    r'|\bwahl|bares für rares|lieder|schlager|musik|\bklassik\b|br-klassik|jazz|bigband|big band|rockpalast|pop around'
    r'|bardentreffen|singspiel|nockherberg|buchmesse|literaturclub|literaturbühne|vor ort|der tag\b|sondersendung'
    r'|\baktuell\b|zdfheute|cinema strikes back|edeltalk|henke|eurovision|\besc\b|abendschau|journal|unterhaltung'
    r'|kurzstrecke|& co\.|\bzoo\b|tierpark|leute heute|drehscheibe|hallo deutschland|kulturzeit|\bnano\b|landesschau'
    r'|hier und heute|kaffee oder tee|volle kanne|wir in bayern|riverboat|nachtcaf|kölner treff|zur sache|fastnacht'
    r'|karneval|fasching|narren|volksfest|umzug|aktenzeichen|wetten, dass|verstehen sie spaß|carmen nebel|silbereisen'
    r'|zarrella|fest der|klein gegen groß|wer weiß|gefragt|quartett|küchenschlacht|strassenstars|\bspezial\b|masterclass'
    r'|frontmen', I)
DOC = re.compile(r'dokumentation|dokumentarfilm|dokudrama|\bdoku\b|doku-|-doku\b|dokuserie|dokureihe|reportage|terra x'
                 r'|universum|planet e|37 grad|37°|die story|exclusiv im ersten|\bre:|leschs kosmos|quarks|abenteuer erde'
                 r'|expeditionen ins tierreich|erlebnis erde|länder - menschen - abenteuer|zdfinfo|history|geschichte - '
                 r'|entdeckung der welt|wissenschaft - |aktuelles und gesellschaft|kultur und pop', I)
DOC_DESC = re.compile(r'dokumentation|dokumentarfilm|\bdoku\b|reportage|dokuserie|dokureihe', I)
KRIMI = re.compile(
    r'tatort|polizeiruf|krimi|kommissar|wilsberg|ein starkes team|die toten vom|nord bei nordwest|friesland|helen dorn'
    r'|marie brand|unter verdacht|neben der spur|sarah kohr|stralsund|kriminalist|schwarzach 23|\bmord|donna leon|wallander'
    r'|inspector|inspektor|detective|sherlock|maigret|barnaby|midsomer|death in paradise|mittsommer|der alte\b|ein fall für'
    r'|soko\b|rosenheim-cops|wapo\b|hafenkante|großstadtrevier|watzmann|die chefin|staatsanwalt|stubbe|bella block'
    r'|nachtschicht|dupin|harter brocken|unter anklage|\bzorn\b|bordertown|the team\b', I)
FILM_T = re.compile(r'spielfilm|fernsehfilm|kinofilm|\bfilm\b|\bfilme\b|\bkino|herzkino|fernsehspiel|filmmittwoch|filmdebüt'
                    r'|komödie|drama|thriller|liebesfilm|romanze|western|abenteuerfilm|actionfilm|familienfilm|märchenfilm'
                    r'|literaturverfilmung|science[- ]fiction|horrorfilm|historienfilm|roadmovie|biopic', I)
FILM_D = re.compile(r'spielfilm|fernsehfilm|kinofilm|komödie|liebesfilm|thriller|drama|western|abenteuerfilm|actionfilm'
                    r'|familienfilm|märchenfilm|literaturverfilmung|science[- ]fiction|horrorfilm|historienfilm|romanze'
                    r'|regie:', I)
SE_MARK = re.compile(r'\(\s*S\s*(\d{1,4})\s*/\s*E\s*(\d{1,4})\s*\)', I)
KIDS = re.compile(r'märchen|sechs auf einen streich|kinderfilm|löwenzahn|schloss einstein|pfefferkörner|die wg\b|sesamstraße'
                  r'|sendung mit der maus|checker|tigerenten|siebenstein|\bpur\+|logo!', I)
GENERIC_THEMA = re.compile(r'film|kino|fernsehspiel|^krimi|krimis$|serie|komödie|drama|thriller|highlight|mediathek|premiere'
                           r'|dokumentation|^doku|dokus$|reportage|^(ard|zdf|arte|3sat|phoenix|br|hr|mdr|ndr|rbb|sr|swr|wdr|one|kika)\b', I)
REIHE_RE = re.compile(r'[-–·]\s*((?:Der|Die|Das|Ein)\s+[\wÄÖÜäöüß.-]+-?[Kk]rimi)\s*$')
EP_RES = [
    SE_MARK,
    re.compile(r'\bS(\d{1,4})\s*E(\d{1,4})\b', I),
    re.compile(r'Staffel\s*(\d{1,4})\D{1,6}Folge\s*(\d{1,4})', I),
    re.compile(r'()\bFolge\s*(\d{1,5})\b', I),
]
GENRE_SUFFIX = (r'Spielfilm|Fernsehfilm|Kinofilm|Drama|Komödie|Tragikomödie|Thriller|Krimi|Kriminalfilm|Liebesfilm|Western'
                r'|Dokumentarfilm|Actionfilm|Abenteuerfilm|Animationsfilm|Familienfilm|Science-Fiction-Film|Horrorfilm'
                r'|Psychothriller|Musikfilm|Historienfilm|Mysterythriller|Kriegsfilm|Liebeskomödie|Coming-of-Age-Film'
                r'|Filmdrama|Kinderfilm|Jugendfilm|Märchenfilm|Literaturverfilmung')
FILM_GENRES = [
    ('Komödie', r'komödie|comedy|\bkomisch|turbulent'),
    ('Liebe & Romantik', r'liebesfilm|romanze|romantisch|liebeskomödie|herzkino|rosamunde pilcher|inga lindström|katie fforde'
                         r'|liebesgeschichte|liebesdrama|traumschiff|kreuzfahrt ins glück|utta danella|lena lorenz|frühling'
                         r'|praxis mit meerblick|ein sommer in|liebe am fjord'),
    ('Drama', r'drama|tragikomödie'),
    ('Thriller', r'thriller'),
    ('Action & Abenteuer', r'actionfilm|actionthriller|\baction\b|abenteuerfilm'),
    ('Sci-Fi & Fantasy', r'science[- ]fiction|sci-fi|fantasy|außerirdisch|dystop|zeitreise'),
    ('Horror & Grusel', r'horror|grusel|schocker|zombie|vampir'),
    ('Western', r'western'),
    ('Historie & Biografie', r'historienfilm|historiendrama|biografie|biopic|wahren begebenheit|wahre geschichte|nach einer wahren'
                             r'|kriegsfilm|kriegsdrama'),
    ('Familie', r'familienfilm|für die ganze familie|kinderfilm|jugendfilm'),
]
GENRE_PRIO = ['Horror & Grusel', 'Sci-Fi & Fantasy', 'Western', 'Action & Abenteuer', 'Thriller', 'Liebe & Romantik', 'Komödie',
              'Historie & Biografie', 'Familie', 'Drama']
DOC_GENRES = [
    ('Natur & Tiere', r'\btier|natur|wildnis|wildlife|ozean|\bmeer|wälder|\bwald\b|safari|vögel|insekt|dschungel|arktis|antarktis'),
    ('Geschichte', r'geschichte|histor|weltkrieg|\bkrieg|nazi|hitler|\bddr\b|mittelalter|römer|antike|kaiser|könig|pharao|wikinger'),
    ('Wissen & Technik', r'wissen|technik|forschung|weltall|universum|kosmos|physik|chemie|medizin|gesundheit|erfindung'
                         r'|künstliche intelligenz|klima'),
    ('Reisen & Länder', r'reise|länder|\binseln?\b|städte|metropole|\balpen|küste|unterwegs|rundreise'),
    ('True Crime', r'true crime|verbrechen|mordfall|kriminalfall|ermittlung|täter|mafia|gefängnis'),
    ('Gesellschaft & Politik', r'politik|gesellschaft|wirtschaft|reportage|story'),
]
DOC_GROUPS = [g for g, _ in DOC_GENRES] + ['Weitere Dokus']
AZ = [('A–C', 'abc'), ('D–F', 'def'), ('G–I', 'ghi'), ('J–L', 'jkl'), ('M–O', 'mno'), ('P–S', 'pqrs'),
      ('T–Z & 0–9', 'tuvwxyz0123456789')]
MAX_PER_DOC_GROUP = 120
MAX_SERIES = 30
MAX_EPISODES = 60
MIN_GENRE = 8


def hms(s):
    try:
        p = [int(x) for x in str(s).split(':')]
    except ValueError:
        return 0
    while len(p) < 3:
        p.insert(0, 0)
    return p[0] * 3600 + p[1] * 60 + p[2]


def expand(base, enc):
    if not enc:
        return ''
    if '|' in enc:
        n, suffix = enc.split('|', 1)
        if n.isdigit():
            return base[:int(n)] + suffix
    return enc if enc.startswith('http') else ''


def parse_filmliste(raw_bytes):
    text = lzma.decompress(raw_bytes).decode('utf-8', errors='replace')
    pairs = json.loads(text, object_pairs_hook=lambda p: p)
    del text
    header = next((v for k, v in pairs if k == 'Filmliste' and isinstance(v, list) and v
                   and str(v[0]).strip().lower() == 'sender'), None)
    names = [re.sub(r'[^a-z]', '', str(h).lower()) for h in (header or [])]

    def idx(name, default):
        return names.index(name) if name in names else default

    ix = dict(sender=idx('sender', 0), thema=idx('thema', 1), titel=idx('titel', 2), datum=idx('datum', 3),
              zeit=idx('zeit', 4), dauer=idx('dauer', 5), beschreibung=idx('beschreibung', 7), url=idx('url', 8),
              url_hd=idx('urlhd', 14), datuml=idx('datuml', 16), geo=idx('geo', 18))
    out, sender, thema = [], '', ''
    width = max(ix.values()) + 1
    for k, v in pairs:
        if k != 'X' or not isinstance(v, list):
            continue
        if len(v) < width:
            v = v + [''] * (width - len(v))
        sender = v[ix['sender']] or sender
        thema = v[ix['thema']] or thema
        url = v[ix['url']]
        ts = int(v[ix['datuml']]) if str(v[ix['datuml']]).isdigit() else 0
        if not ts:
            try:
                ts = int(dt.datetime.strptime(f"{v[ix['datum']]} {v[ix['zeit']] or '00:00:00'}", '%d.%m.%Y %H:%M:%S').timestamp())
            except ValueError:
                ts = 0
        out.append(dict(sender=sender, thema=thema, titel=v[ix['titel']], desc=v[ix['beschreibung']],
                        dur=hms(v[ix['dauer']]), ts=ts, url=url, url_hd=expand(url, v[ix['url_hd']]), geo=v[ix['geo']]))
    return out


def mvw_records():
    """Ersatzquelle: MediathekViewWeb-API (gezielte Suchen)."""
    out = []
    queries = [('topic', 'film'), ('topic', 'spielfilm'), ('topic', 'fernsehfilm'), ('topic', 'kino'), ('topic', 'herzkino'),
               ('topic', 'tatort'), ('topic', 'polizeiruf'), ('topic', 'krimi'), ('title', 'krimi'), ('topic', 'doku'),
               ('topic', 'dokumentation'), ('topic', 'märchen'), ('description', 'spielfilm'), ('description', 'fernsehfilm')]
    for field, q in queries:
        for page in range(4):
            body = {'queries': [{'fields': [field], 'query': q}], 'sortBy': 'timestamp', 'sortOrder': 'desc',
                    'future': False, 'offset': page * 250, 'size': 250, 'duration_min': 40 * 60}
            res = json.loads(fetch('https://mediathekviewweb.de/api/query', data=json.dumps(body).encode(),
                                   headers={'Content-Type': 'text/plain'}, timeout=60))
            items = ((res or {}).get('result') or {}).get('results') or []
            for it in items:
                out.append(dict(sender=it.get('channel', ''), thema=it.get('topic', ''), titel=it.get('title', ''),
                                desc=it.get('description', ''), dur=int(it.get('duration') or 0),
                                ts=int(it.get('timestamp') or 0), url=it.get('url_video', ''),
                                url_hd=it.get('url_video_hd', ''), geo=''))
            if len(items) < 250:
                break
            time.sleep(1)
    return out


def tidy_title(t, thema=''):
    t = (t or '').replace('"', "'")
    t = re.sub(r'\s*\((?:mit )?(?:Untertitel|UT)\)', '', t, flags=I)
    t = re.sub(r'\s*\|\s*restauriert in HD', '', t, flags=I)
    t = SE_MARK.sub('', t)
    t = re.sub(r'\s*[-–|·]\s*(?:Der|Ein)\s+(?:Samstags|Sonntags|Montags|Dienstags|Mittwochs|Donnerstags|Freitags)krimi\b', '', t, flags=I)
    t = re.sub(r'\s*[-–]\s*Aus der Herzkino-Reihe.*$', '', t, flags=I)
    t = re.sub(r'\s*[-–]\s*Nach (?:der|dem|den|einer|einem) .*$', '', t, flags=I)
    t = re.sub(r'\s*[-–]\s*(?:' + GENRE_SUFFIX + r')\b[^-–]*?\d{4}\s*$', '', t, flags=I)
    t = re.sub(r'\s*[-–|:]\s*(?:Spielfilm|Fernsehfilm|Film|Kinofilm)\s*$', '', t, flags=I)
    t = re.sub(r'^(?:Spielfilm|Fernsehfilm|Film|Kinofilm)\s*[:–-]\s*', '', t, flags=I)
    th = (thema or '').strip()
    if th and not GENERIC_THEMA.search(th):
        t = re.sub(r'\s*[-–·|:]\s*' + re.escape(th) + r'(?:\s*\(\d+\))?\s*$', '', t, flags=I)
    return re.sub(r'\s+', ' ', t).strip(' -–|·:')


def display_name(thema, titel):
    t = tidy_title(titel, thema)
    th = (thema or '').replace('"', "'").strip()
    if not th or th.lower() in t.lower() or t.lower() in th.lower() or GENERIC_THEMA.search(th):
        return t or th
    return f'{th} – {t}'


def core_key(name):
    k = re.sub(r'\([^)]*\)', ' ', name.lower())
    k = re.sub(r'^(tatort|polizeiruf 110)\s*[-–:]\s*', '', k)
    return re.sub(r'[^a-z0-9äöüß]', '', k)


def az_key(name):
    k = name.lower().replace('ä', 'a').replace('ö', 'o').replace('ü', 'u').replace('ß', 'ss')
    k = re.sub(r'^[^a-z0-9]+', '', k)
    return re.sub(r'^(der|die|das|ein|eine|the|a|an)\s+', '', k)


def episode(title):
    for rx in EP_RES:
        m = rx.search(title)
        if m:
            s = int(m.group(1)) if m.group(1) else 0
            e = int(m.group(2))
            rest = tidy_title((title[:m.start()] + ' ' + title[m.end():]).strip(' -–:,|'))
            return s, e, rest
    return None


def build_vod(records, logos):
    films, krimis, kids, docs = [], [], [], []
    series = collections.defaultdict(list)
    debug = collections.defaultdict(lambda: [0, '', collections.Counter()])
    for r in records:
        sender = (r['sender'] or '').strip().upper()
        if sender not in SENDER_OK or not r['url']:
            continue
        geo = (r.get('geo') or '').upper()
        if geo and 'DE' not in geo.split('-'):
            continue
        if r['ts'] > NOW + 3600 or r['dur'] < 15 * 60:
            continue
        thema, titel, desc = r['thema'] or '', r['titel'] or '', r['desc'] or ''
        tt = f'{thema} {titel}'
        if EXCL.search(tt):
            continue
        dur = r['dur']
        long_ = 70 * 60 <= dur <= 200 * 60
        r = dict(r, sender=sender, url=r['url_hd'] or r['url'])
        cat = None
        if sender in KIDS_SENDER or KIDS.search(tt):
            cat = 'kids' if dur >= 45 * 60 and not NOFILM.search(tt) else None
        elif DOC.search(tt):
            cat = 'doc' if dur >= 30 * 60 else None
        elif NOFILM.search(tt) or sender == 'PHOENIX':
            cat = None
        elif KRIMI.search(tt) and 75 * 60 <= dur <= 200 * 60:
            cat = 'krimi'
        elif FILM_T.search(tt) and long_:
            cat = 'film'
        elif DOC_DESC.search(desc) and dur >= 30 * 60:
            cat = 'doc'
        elif long_ and (FILM_D.search(desc) or SE_MARK.search(titel)):
            cat = 'film'
        elif 20 * 60 <= dur <= 70 * 60:
            ep = episode(titel)
            if ep and ep[0] < 1900:
                cat = 'series'
        if DEBUG and dur >= 70 * 60:
            d = debug[(sender, thema)]
            d[0] += 1; d[1] = d[1] or titel; d[2][cat or '-'] += 1
        if cat == 'series':
            series[thema.strip()].append(r)
        elif cat:
            {'film': films, 'krimi': krimis, 'kids': kids, 'doc': docs}[cat].append(r)

    out = []

    def item(group, r, name):
        if not name or name.lower() == group.split(' | ', 1)[1].lower():
            return
        out.append(dict(group=group, name=name, logo=logos.get(r['sender'], ''), tvgid='', url=r['url'], opts=[]))

    def dedupe(rs, skip=()):
        seen, res = set(skip), []
        for r in sorted(rs, key=lambda x: -x['ts']):
            nm = display_name(r['thema'], r['titel'])
            key = core_key(nm)
            if not key or key in seen:
                continue
            seen.add(key); res.append((r, nm))
        return res

    # Krimis (zuerst, damit ein Krimi nicht zusätzlich als Film auftaucht)
    krimi_items = dedupe(krimis)
    krimi_keys = {core_key(nm) for _, nm in krimi_items}
    reihe = []
    for r, nm in krimi_items:
        if re.match(r'tatort\b', r['thema'].strip(), I) or re.match(r'tatort\b', r['titel'].strip(), I):
            g = 'Tatort'
        elif re.match(r'polizeiruf', r['thema'].strip(), I) or re.match(r'polizeiruf', r['titel'].strip(), I):
            g = 'Polizeiruf 110'
        else:
            th = r['thema'].strip()
            m = REIHE_RE.search(r['titel'])
            g = th if th and not GENERIC_THEMA.search(th) else (m.group(1) if m else 'Weitere Krimis')
        reihe.append(g)
    counts = collections.Counter(reihe)
    fixed = ('Tatort', 'Polizeiruf 110', 'Weitere Krimis')
    big = [g for g, n in counts.most_common() if g not in fixed and n >= 6][:12]
    groups = collections.defaultdict(list)
    for (r, nm), g in zip(krimi_items, reihe):
        if g not in fixed and g not in big:
            g = 'Weitere Krimis'
        if g in ('Tatort', 'Polizeiruf 110') and not nm.lower().startswith(g.split()[0].lower()):
            nm = f'{g} – {nm}'
        elif g not in fixed:
            nm = re.sub(r'\s*[-–·]\s*' + re.escape(g) + r'\s*$', '', nm).strip(' -–·') or nm
            if not nm.lower().startswith(g.lower()):
                nm = f'{g} – {nm}'
        groups[g].append((r, nm))
    krimi_out = []
    for g in ['Tatort', 'Polizeiruf 110'] + sorted(big, key=str.lower) + ['Weitere Krimis']:
        for r, nm in sorted(groups.get(g, []), key=lambda x: az_key(x[1])):
            krimi_out.append((f'KRIMIS | {g}', r, nm))

    # Filme: Neu, Genres, dann alle Filme von A bis Z
    film_items = dedupe(films, skip=krimi_keys)
    for r, nm in [x for x in film_items if x[0]['ts'] >= NOW - 14 * 86400][:80]:
        item('FILME | Neu', r, nm)
    by_genre = collections.defaultdict(list)
    for r, nm in film_items:
        text = f"{r['thema']} {r['titel']} {r['desc']}"
        hits = [g for g, rx in FILM_GENRES if re.search(rx, text, I)]
        if hits:
            by_genre[min(hits, key=GENRE_PRIO.index)].append((r, nm))
    for g, _ in FILM_GENRES:
        rows = sorted(by_genre.get(g, []), key=lambda x: az_key(x[1]))
        if len(rows) >= MIN_GENRE:
            for r, nm in rows:
                item(f'FILME | {g}', r, nm)
    by_letter = collections.defaultdict(list)
    for r, nm in film_items:
        k = az_key(nm)
        g = next((label for label, chars in AZ if k[:1] in chars), AZ[-1][0])
        by_letter[g].append((r, nm))
    for label, _ in AZ:
        for r, nm in sorted(by_letter.get(label, []), key=lambda x: az_key(x[1])):
            item(f'FILME | {label}', r, nm)

    for g, r, nm in krimi_out:
        item(g, r, nm)

    # Kinder
    for r, nm in dedupe(kids)[:150]:
        item('KINDER | Filme & Märchen', r, nm)

    # Dokus
    by_doc = collections.defaultdict(list)
    for r, nm in dedupe(docs):
        text = f"{r['thema']} {r['titel']} {r['desc']}"
        g = next((g for g, rx in DOC_GENRES if re.search(rx, text, I)), 'Weitere Dokus')
        by_doc[g].append((r, nm))
    for g in DOC_GROUPS:
        for r, nm in by_doc.get(g, [])[:MAX_PER_DOC_GROUP]:
            item(f'DOKUS | {g}', r, nm)

    # Serien: die Reihen mit den meisten verfügbaren Folgen
    cand = []
    for th, rs in series.items():
        eps = {}
        for r in sorted(rs, key=lambda x: -x['ts']):
            ep = episode(r['titel'])
            if ep and (ep[0], ep[1]) not in eps:
                eps[(ep[0], ep[1])] = (r, ep)
        if th and len(eps) >= 4:
            cand.append((th, eps))
    ranked = sorted(cand, key=lambda x: -len(x[1]))[:MAX_SERIES]
    for th, eps in sorted(ranked, key=lambda x: az_key(x[0])):
        chosen = sorted(eps.values(), key=lambda x: (x[1][0], x[1][1]))[-MAX_EPISODES:]
        for r, (s, e, rest) in chosen:
            label = f'S{s:02d} E{e:02d}' if s else f'Folge {e}'
            item(f'SERIEN | {th.replace(chr(34), chr(39))}', r, f'{label} – {rest}' if rest else label)

    stats = dict(filme=len(film_items), krimis=len(krimi_items), kinder=len(kids), dokus=len(docs),
                 serien=len(ranked), eintraege=len(out))
    if DEBUG:
        rows = sorted(debug.items(), key=lambda kv: -kv[1][0])[:500]
        with open(DEBUG_FILE, 'w', encoding='utf-8') as f:
            f.write('Anzahl | Sender | Thema | Beispieltitel | Kategorien (nur Einträge ab 70 Min.)\n')
            for (s, th), (n, ex, c) in rows:
                f.write(f"{n:4} | {s} | {th} | {ex} | {', '.join(f'{k}:{v}' for k, v in c.most_common())}\n")
    return out, stats


# ---------------------------------------------------------------- Ausgabe

def write_m3u(path, entries):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('#EXTM3U\n')
        for e in entries:
            name = e['name'].replace('\n', ' ').replace('"', "'")
            tvg_name = f' tvg-name="{name}"' if ',' not in name else ''
            attrs = f'tvg-id="{e["tvgid"]}"{tvg_name} tvg-logo="{e["logo"]}" group-title="{e["group"]}"'
            f.write(f'#EXTINF:-1 {attrs},{name}\n')
            for o in e.get('opts') or []:
                f.write(o + '\n')
            f.write(e['url'] + '\n')


def previous_entries():
    if not os.path.exists(OUT):
        return [], []
    old = parse_m3u(open(OUT, encoding='utf-8').read())
    conv = [dict(group=x['attrs'].get('group-title', ''), name=x['name'], logo=x['attrs'].get('tvg-logo', ''),
                 tvgid=x['attrs'].get('tvg-id', ''), url=x['url'], opts=x['opts']) for x in old]
    vod = [e for e in conv if e['group'].startswith(VOD_PREFIXES)]
    live = [e for e in conv if not e['group'].startswith(VOD_PREFIXES)]
    return live, vod


def main():
    old_live, old_vod = previous_entries()
    status = {}

    # Live
    try:
        src = {
            'kodi': parse_m3u(fetch(KODI + 'clean_tv.m3u').decode('utf-8', 'replace')),
            'kodi_local': parse_m3u(fetch(KODI + 'clean_tv_local.m3u').decode('utf-8', 'replace')),
            'de': parse_m3u(fetch(IPTV_ORG + 'countries/de.m3u').decode('utf-8', 'replace')),
            'deu': parse_m3u(fetch(IPTV_ORG + 'languages/deu.m3u').decode('utf-8', 'replace')),
            'tr': parse_m3u(fetch(IPTV_ORG + 'countries/tr.m3u').decode('utf-8', 'replace')),
            'tur': parse_m3u(fetch(IPTV_ORG + 'languages/tur.m3u').decode('utf-8', 'replace')),
            'movies': parse_m3u(fetch(IPTV_ORG + 'categories/movies.m3u').decode('utf-8', 'replace')),
        }
        live = build_live(src)
        if len(live) < 250:
            raise RuntimeError(f'nur {len(live)} Live-Sender gefunden')
        status['live'] = f'{len(live)} Sender (neu gebaut)'
    except Exception as e:  # noqa: BLE001
        log(f'Live-Sender: Fehler – {e}')
        src = {'kodi': []}
        live = old_live
        status['live'] = f'{len(live)} Sender (alte Liste behalten, Fehler: {e})'
    logos = {}
    by_name = {x['name'].strip(): x['attrs'].get('tvg-logo', '') for x in src.get('kodi', [])}
    by_name.update({e['name']: e['logo'] for e in live})
    for s, n in SENDER_LOGO_NAME.items():
        logos[s] = by_name.get(n, '')

    # Filme & Co.
    vod, records, source = [], None, ''
    local = os.environ.get('FILMLISTE_FILE')
    try:
        if local:
            records, source = parse_filmliste(open(local, 'rb').read()), f'Datei {local}'
        else:
            err = None
            for u in FILMLISTE_URLS:
                try:
                    records, source = parse_filmliste(fetch(u, timeout=300, tries=2)), 'MediathekView-Filmliste'
                    break
                except Exception as e:  # noqa: BLE001
                    err = e
                    log(f'Filmliste {u}: {e}')
            if records is None:
                raise RuntimeError(err)
    except Exception as e:  # noqa: BLE001
        log(f'Filmliste nicht verfügbar ({e}), versuche MediathekViewWeb …')
        try:
            records, source = mvw_records(), 'MediathekViewWeb'
        except Exception as e2:  # noqa: BLE001
            log(f'MediathekViewWeb: Fehler – {e2}')
    vstats = {}
    if records:
        log(f'{len(records)} Mediathek-Einträge gelesen ({source})')
        vod, vstats = build_vod(records, logos)
    if len(vod) >= int(os.environ.get('MIN_VOD', '50')):
        status['vod'] = (f"{len(vod)} Einträge aus {source}: {vstats.get('filme')} Filme, {vstats.get('krimis')} Krimis, "
                         f"{vstats.get('serien')} Serien, {vstats.get('dokus')} Dokus, {vstats.get('kinder')} Kinderfilme")
    else:
        status['vod'] = f'{len(old_vod)} Einträge (alte Liste behalten; neu nur {len(vod)})'
        vod = old_vod

    top = [e for e in live if e['group'] not in LIVE_BOTTOM]
    bottom = [e for e in live if e['group'] in LIVE_BOTTOM]
    write_m3u(OUT, top + vod + bottom)

    groups = collections.Counter(e['group'] for e in top + vod + bottom)
    with open(STATS, 'w', encoding='utf-8') as f:
        f.write(f"Stand: {dt.datetime.now(dt.timezone.utc):%Y-%m-%d %H:%M} UTC\n")
        f.write(f"Live: {status['live']}\nFilme & Co.: {status['vod']}\n\nKategorien:\n")
        for g, n in groups.items():
            f.write(f'{n:5}  {g}\n')
        if LOG:
            f.write('\nMeldungen:\n' + '\n'.join(LOG) + '\n')
    log(f"Live: {status['live']}")
    log(f"Filme & Co.: {status['vod']}")


if __name__ == '__main__':
    sys.exit(main())
