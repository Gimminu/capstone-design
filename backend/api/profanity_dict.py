"""
한국어 욕설/비속어 사전 및 변형 패턴
카테고리: profanity(비속어), slur(혐오 표현), sexual(성희롱)
"""
import re

# ── 화이트리스트 (오탐 방지) ────────────────────────────
# 모델이 욕설로 오분류하기 쉬운 정상 단어들
WHITELIST = {
    "응디", "앵맹", "맘마", "응애", "엄마", "노무현", "문재인",
    "맘마", "할매", "할미", "아가", "아기", "꺄", "꺄힣",
    "히히", "후후", "하하", "호호", "키키", "크크",
    "시발점", "시발역", "시발택시",  # 실제 단어
    "씨앗", "새끼손가락", "새끼줄", "새끼고양이", "새끼강아지",
    "병신도", "병신자",  # 병신도(지리), 병신자(종교)
    "존버", "존맛", "존예", "존잘",  # 존X 긍정 표현
    "지능", "지능높",
}

# ── 욕설 패턴 사전 ───────────────────────────────────
# (정규식 패턴, 대표 단어, 카테고리)
PROFANITY_PATTERNS = [
    # ── 비속어 (profanity) ──────────────────────────────
    # 씨발 계열
    (r"씨[이\s]*발|시[이\s]*발|씨[이\s]*팔|시[이\s]*팔|씨[이\s]*벌|ㅅㅂ|ㅆㅂ|씨[0-9]*발|시[0-9]*발|싸[이]?발|쒸[이]?발|씌발|c발|씨bal|니미씨|니미시|시불|시블|씨부[랄럴렁]|시부[랄럴렁]|s{1,2}[\W_]*(?:h[\W_]*)?i[\W_]*b[\W_]*a[\W_]*l|tlqkf", "씨발", "profanity"),
    # 개새끼 계열
    (r"개[새세][끼키]|개[새세]끼|개[새세]기|개색[끼키]|개[쌔쎄][끼키]|개[0-9]*새끼|ㄱㅅㄲ|gae[\W_]*s(?:ae|e|a)[\W_]*k{1,2}i|rotori", "개새끼", "profanity"),
    # 병신 계열
    (r"병[\.]*신|by[eou]+ng[\W_]*s?in|q[\W_]*u[\W_]*d[\W_]*t[\W_]*l[\W_]*s|qudtkf|ㅂㅅ|병[0-9]*신|빙신|벼ㅇ신|병딱|병1신", "병신", "profanity"),
    # 지랄 계열
    (r"지[이\s]*랄|ㅈㄹ|지[0-9]*랄|jiral|wlfkf", "지랄", "profanity"),
    # 좆/좃 계열
    (r"좆|좇|조[까깟]|졸라|존나|ㅈㄴ|존[0-9]*나|좃|좋까|존[나]?맛|좆같|jonna|whssk", "좆", "profanity"),
    # 씹 계열
    (r"씹|십[새세][끼키]|씹[새세][끼키]|씹할|ㅆ[0-9]*ㅂ|씹덕", "씹", "profanity"),
    # 꺼져/닥쳐 계열
    (r"꺼[져저]|닥[쳐쳐]|닥치|닥[0-9]*쳐|꺼[0-9]*져|k{1,2}eoj(?:ye)?o|rjwu", "꺼져", "profanity"),
    # 미친 계열
    (r"미친[놈년]|미친[새]끼|미[0-9]*친|ㅁㅊ|미놈|미년|michin|alcls", "미친", "profanity"),
    # 새끼 단독
    (r"새[끼키]|색[끼키]|쌔[끼키]|ㅅㄲ", "새끼", "profanity"),
    # 엿/엿먹 계열
    (r"엿[먹머]|엿이나|엿같", "엿", "profanity"),
    # 뒤질/뒤져/죽어 계열
    (r"뒤[질져저]|뒈[질져저]|죽[어을]래|죽여|뒤[진]다", "뒤져", "profanity"),
    # 찐따
    (r"찐따|찐[찌]|찐다", "찐따", "profanity"),
    # 쓰레기
    (r"쓰[레래]기", "쓰레기", "profanity"),
    # 개 (접두 강조) + 부정 표현
    (r"개[같갓]은|개[못몬]된|개[짜자]증|개[쓰스]레기|개[소쇼]리|개[한]?[심]|개[역]겨[운움]|개[쩔절]어|개[빡]치", "개-", "profanity"),
    # 느금마 계열 (니 단독 매칭 방지 — 최소 2음절 이상 필수)
    (r"느[금끔]마|니[금끔]마|느그[엄]마|니미[씨시]|니미[발벌팔불]|니[미]씨[발벌팔불][럴랄]?", "느금마", "profanity"),
    # 한국 인터넷 비속어
    (r"ㅄ|ㄷㅊ|ㅂㄹ", "비속어", "profanity"),

    # 영문/로마자 욕설 및 변형
    (r"f[\W_]*u[\W_]*c[\W_]*k(?:[\W_]*i[\W_]*n[\W_]*g)?", "fuck", "profanity"),
    (r"s[\W_]*h[\W_]*i[\W_]*t", "shit", "profanity"),
    (r"b[\W_]*i[\W_]*t[\W_]*c[\W_]*h", "bitch", "profanity"),
    (r"a[\W_]*s[\W_]*s[\W_]*h[\W_]*o[\W_]*l[\W_]*e|bastard|dick|pussy|slut|whore|cunt|prick|twat|wanker|mother[\W_]*fucker|douchebag", "english-profanity", "profanity"),
    (r"(?<!\w)(?:puta|puto|mierda|joder|cabron|cabr[oó]n|pendejo|gilipollas|co[nñ]o|chingad[ao]|maric[oó]n)(?!\w)", "spanish-profanity", "profanity"),
    (r"(?<!\w)(?:putain|merde|connard|salope|encul[eé]|ta[\W_]+gueule|nique[\W_]+ta[\W_]+m[eè]re)(?!\w)", "french-profanity", "profanity"),
    (r"(?<!\w)(?:schei(?:ss|ß)e|arschloch|wichser|fotze)(?!\w)", "german-profanity", "profanity"),
    (r"(?<!\w)(?:porra|caralho|viado)(?!\w)", "portuguese-profanity", "profanity"),
    (r"(?<!\w)(?:orospu|siktir)(?!\w)", "turkish-profanity", "profanity"),
    (r"бля(?:дь|ть)?|сука|хуй|пизд[аеуы]?|еба(?:ть|н[а-я]*)|мудак|долбо[её]б", "russian-profanity", "profanity"),
    (r"くそ|クソ|馬鹿|バカ|死ね|操你妈|草你妈|傻逼|他妈的|去死|كسمك|كس امك|ابن الكلب", "multilingual-profanity", "profanity"),
    (r"nigg(?:er|a)|faggot|retard", "english-slur", "slur"),

    # ── 성희롱/성적 표현 (sexual) ──────────────────────
    (r"젖[탱통]이|보[지]$|자[지]$|음[경]|딸딸이|딸치[기는]|야[동]|강[간]|성[폭]력|성[추]행|성[희]롱", "성적표현", "sexual"),
    (r"몸[매]보[여자]|벗[어]봐|까[줘주]봐|만[져질짐]래|빨[아어]줘|따[먹]", "성희롱", "sexual"),
    (r"보빨|잠[지]|봊|보[짓]물|음[란탕]|변[태]|노[출]증", "성적비하", "sexual"),

    # ── 혐오 표현 (slur) ───────────────────────────────
    # 커뮤니티/정치 은어
    (r"일베(?:충|놈|새끼|쉑|ㅊ)?|일간베스트|실베(?:충)?|네일베|ㅇㅂ(?:충)?", "일베", "slur"),
    (r"메갈(?:년|련|충)?|메갈리아|워마드(?:충)?|보슬아치|한남(?:충)?|재기(?:해|해라|했네)?|느금마|느개비", "메갈", "slur"),
    (r"민주화(?:시키|당하|버튼|보소)?|운지(?:했|해|하)?|고인드립|슨상님|노짱", "일베은어", "slur"),
    (r"홍어(?:새끼|충|년|놈|족)?|전라디언|좌좀|좌빨|문슬람|빨갱이|종북", "정치지역혐오", "slur"),
    # 성별 혐오
    (r"한남충|한남[들]|한남[놈년]", "한남충", "slur"),
    (r"김치녀|김[치]녀|된장녀|맘충|보혐|여혐", "성별혐오", "slur"),
    # 세대 혐오
    (r"틀딱[들]?|틀[딱딱]이|노[인]네|꼰[대]", "틀딱", "slur"),
    # 외국인 혐오
    (r"외노자[들]?|똥남아|짱[깨게]|쪽바리|깜[둥동]이|조선족[놈년]?", "외국인비하", "slur"),
    # 장애 혐오
    (r"장애[인]?[놈년새]|애자|불구|정[신]병[자]?", "장애비하", "slur"),
    # 성소수자 혐오
    (r"게이[놈년]|호모[놈년]|레즈[놈년]|쉬[메멜]|트[랜렌]스[젠]?[더]?[놈년]?", "성소수자비하", "slur"),
]

# 초성 욕설 패턴
CHOSUNG_PROFANITY = {
    "ㅅㅂ": ("씨발", "profanity"),
    "ㅆㅂ": ("씨발", "profanity"),
    "ㅂㅅ": ("병신", "profanity"),
    "ㅈㄹ": ("지랄", "profanity"),
    "ㅈㄴ": ("존나", "profanity"),
    "ㅅㄲ": ("새끼", "profanity"),
    "ㄱㅅㄲ": ("개새끼", "profanity"),
    "ㅁㅊ": ("미친", "profanity"),
    "ㄲㅈ": ("꺼져", "profanity"),
    "ㄷㅊ": ("닥쳐", "profanity"),
    "ㄴㄱㅁ": ("느금마", "profanity"),
}


def compile_patterns():
    """정규식 패턴을 미리 컴파일하여 반환"""
    compiled = []
    for pattern, canonical, category in PROFANITY_PATTERNS:
        compiled.append((re.compile(pattern, re.IGNORECASE), canonical, category))
    return compiled


COMPILED_PATTERNS = compile_patterns()
