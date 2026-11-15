// =============================================================
//  《木生遇淑柔》歌词动画 MV —— 工程文件（可直接手改，也可在预览页里改后导出）
//  - lyrics : 歌词时间轴（秒），kw = 需要强调的关键词（会变色 + 弹跳 + 驱动画面事件）
//  - shots  : 分镜。每个镜头只写开始时间，结束 = 下一个镜头的开始
//  - theme  : 视觉主题：配色、字体、贯穿元素（木轮）
// =============================================================
window.MV_DEFAULT_PROJECT = {
  version: 1,
  meta: {
    title: "木生遇淑柔",
    credits: "",            // 片尾字幕，例如 "词：xx  曲：xx  演唱：xx"
    lyricOffset: 0,         // 全局歌词偏移（秒），整体偏早/偏晚时调这里
    fps: 30,
    width: 1920,
    height: 1080
  },

  theme: {
    name: "木轮 · 春溪",
    motif: "木轮：开场滚入 → 眼眸瞳孔 → 溪水涟漪 → 手削车轮 → 星夜明月 → 片尾印章",
    fonts: {
      lyric: "MaShanZheng",      // 马善政毛笔楷书：歌词
      title: "MaShanZheng",
      ui: "NotoSerifSC"          // 思源宋体：小字、标签
    },
    palettes: {
      paper:  { bg1:"#F5ECDD", bg2:"#E7D7BE", ink:"#3A2A20", wood:"#A8703F", woodDark:"#6B4428", accent:"#C4473A", water:"#6FA8A0", petal:"#E9A3A3", glow:"#FFDCA8", text:"#3A2A20", crowd:"#B9A58C" },
      stream: { bg1:"#EEF3EC", bg2:"#CFE1D8", ink:"#24363A", wood:"#9C6B3F", woodDark:"#5E4029", accent:"#D0604E", water:"#5E9C98", petal:"#F0A9A6", glow:"#FFE2B5", text:"#22343A", crowd:"#9DB3AA" },
      spring: { bg1:"#FDF1E8", bg2:"#F5CFC6", ink:"#5A2E2A", wood:"#B07444", woodDark:"#6E4128", accent:"#D64F55", water:"#7DB5AE", petal:"#F3A1B4", glow:"#FFE6C2", text:"#4E2622", crowd:"#E0B2A8" },
      night:  { bg1:"#121A30", bg2:"#2B3A63", ink:"#E9E2CF", wood:"#C99A62", woodDark:"#8A6440", accent:"#F2C46D", water:"#3E6C86", petal:"#F4D9A8", glow:"#F7D88E", text:"#F4ECD9", crowd:"#3A4A72" },
      dawn:   { bg1:"#FFE9CF", bg2:"#F4B496", ink:"#5B2F25", wood:"#B57846", woodDark:"#70432A", accent:"#E0573F", water:"#76AFB0", petal:"#F7A7A1", glow:"#FFF0C9", text:"#4D261D", crowd:"#E3A88E" },
      dusk:   { bg1:"#F3DCC0", bg2:"#D7A479", ink:"#3F271B", wood:"#9E6838", woodDark:"#5F3A22", accent:"#B8452F", water:"#6E9C96", petal:"#EBA690", glow:"#FFD08A", text:"#3A2418", crowd:"#C49470" }
    }
  },

  lyrics: [
    { t: 14.56,  text: "人群熙攘 视线偶然相撞",          kw: ["相撞"] },
    { t: 17.84,  text: "那一瞥惊鸿 从此念念不忘",        kw: ["惊鸿"] },
    { t: 21.36,  text: "怕时光太匆忙 怕身份太平常",      kw: ["匆忙"] },
    { t: 24.92,  text: "满腹心事 只敢叹一句眉目好看",    kw: ["眉目好看"] },
    { t: 28.64,  text: "我叫木生 木头的木 生命的生",      kw: ["木生"] },
    { t: 31.96,  text: "你唤淑柔 温柔的柔 似水长流",      kw: ["淑柔"] },
    { t: 35.72,  text: "一读你名，情意慢慢游",            kw: ["慢慢游"] },
    { t: 39.04,  text: "怕一转身 就错过这唯一的邂逅",    kw: ["转身"] },
    { t: 43.26,  text: "今天有幸 遇见你的眼眸",          kw: ["眼眸"] },
    { t: 46.28,  text: "后天是否 还能再见你笑容",        kw: ["笑容"] },
    { t: 50.28,  text: "跌入溪水 湿透了衣袖",            kw: ["溪水"] },
    { t: 53.32,  text: "相视一笑 答案已在心头",          kw: ["相视一笑"] },
    { t: 56.24,  text: "最幸福的事 是我也喜欢你",        kw: ["喜欢你"] },
    { t: 59.92,  text: "恰好的默契 让空气都甜蜜",        kw: ["甜蜜"] },
    { t: 63.32,  text: "你的回答 像春风拂过心底",        kw: ["春风"] },
    { t: 67.28,  text: "连呼吸之间 都藏着香甜气息",      kw: ["呼吸"] },
    { t: 99.24,  text: "不必许诺，不必盛大的话",          kw: [] },
    { t: 101.30, text: "拙手削木，搭起单车一架",          kw: ["单车"] },
    { t: 103.06, text: "木轮悠悠，驮着心底牵挂",          kw: ["木轮"] },
    { t: 104.86, text: "你不嫌弃，我这朴素人家",          kw: ["朴素人家"] },
    { t: 106.76, text: "懂我心意 胜过万千繁华",          kw: ["懂我心意"] },
    { t: 109.84, text: "握住双手 就不再害怕",            kw: ["握住双手"] },
    { t: 114.28, text: "(Woo~ woo~)",                    kw: [], style: "adlib" },
    { t: 115.56, text: "木质车轮 转动着年华",            kw: ["车轮"] },
    { t: 118.28, text: "(Woo~ woo~)",                    kw: [], style: "adlib" },
    { t: 119.08, text: "你在后座 笑靥如花",              kw: ["笑靥如花"] },
    { t: 127.92, text: "晚风漫过 木轮的辙痕",            kw: ["辙痕"] },
    { t: 131.00, text: "星子轻落 你眼底温存",            kw: ["星子"] },
    { t: 134.96, text: "不问远方 来日有多深",            kw: [] },
    { t: 138.08, text: "同乘一车 一同共赴晨昏",          kw: ["晨昏"] },
    { t: 141.03, text: "最幸福的事 是我也喜欢你",        kw: ["喜欢你"] },
    { t: 144.48, text: "恰好的默契 让空气都甜蜜",        kw: ["甜蜜"] },
    { t: 148.04, text: "你的回答 像春风拂过心底",        kw: ["春风"] },
    { t: 152.00, text: "连呼吸之间 都藏着香甜气息",      kw: ["呼吸"] },
    { t: 164.12, text: "木生遇淑柔",                      kw: ["木生", "淑柔"] },
    { t: 167.64, text: "此生复何求",                      kw: [] }
  ],

  // 段落标记（来自 LRC 的 [Intro]/[Verse] 标签）——用于时间轴着色 & 歌词收尾
  sections: [
    { t: 1.88, name: "Intro" }, { t: 8.16, name: "Verse 1" }, { t: 42.16, name: "Pre-Chorus" },
    { t: 55.88, name: "Chorus" }, { t: 85.32, name: "Interlude" }, { t: 97.52, name: "Verse 2" },
    { t: 113.48, name: "Post-Chorus" }, { t: 122.20, name: "Bridge" }, { t: 140.60, name: "Chorus" },
    { t: 161.76, name: "Outro" }, { t: 171.00, name: "Tail" }
  ],

  // 分镜：scene = 场景模板；palette = 配色；transition = 进入本镜头的转场
  // lyric.mode: center 居中 / lower 下三分之一 / upper 上方 / vertical 竖排（右侧）
  shots: [
    { id:"s01", name:"序 · 木轮入画",     start:0,      scene:"title",    palette:"paper",  transition:{type:"ink",    dur:1.2}, lyric:{mode:"lower",    size:76}, params:{},
      note:"木纹线条随前奏铺开，木轮从左滚入停在正中；鼓点让辐条发光；标题逐字晕开" },
    { id:"s02", name:"主歌一 · 人海一瞥", start:14.0,   scene:"crowd",    palette:"paper",  transition:{type:"wheel",  dur:1.0}, lyric:{mode:"upper",    size:72}, params:{},
      note:"人群剪影左右流动；『相撞』两道目光相遇迸出火花；『惊鸿』白鸟掠过；『匆忙』人潮加速；『眉目好看』木轮化作瞳孔" },
    { id:"s03", name:"主歌一 · 名字",     start:28.2,   scene:"names",    palette:"paper",  transition:{type:"ink",    dur:0.9}, lyric:{mode:"lower",    size:72}, params:{},
      note:"巨大的『木』带木纹、『生』发芽；『柔』化作流水；『慢慢游』两字相向漂近；『转身』两人背对险些错过" },
    { id:"s04", name:"导歌 · 溪水",       start:41.9,   scene:"stream",   palette:"stream", transition:{type:"ripple", dur:1.2}, lyric:{mode:"upper",    size:70}, params:{},
      note:"溪边倒影；『后天』木轮作太阳划过天空；『溪水』落水、涟漪 = 木轮辐条；『相视一笑』暖光扩散" },
    { id:"s05", name:"副歌一 · 春风",     start:55.88,  scene:"spring",   palette:"spring", transition:{type:"wheel",  dur:0.7}, lyric:{mode:"center",   size:96}, params:{intensity:1},
      note:"花瓣随强拍从木轮中心炸开；『春风』一阵风把花瓣吹过画面；『呼吸』整幅画面随呼吸起伏" },
    { id:"s06", name:"副歌余韵",          start:71.0,   scene:"spring",   palette:"spring", transition:{type:"ink",    dur:1.6}, lyric:{mode:"center",   size:96}, params:{intensity:0.6, echo:"最幸福的事"},
      note:"副歌尾段（歌词文件未标注的哼唱），保留花瓣与木轮，回声字缓缓淡出" },
    { id:"s07", name:"间奏 · 削木成轮",   start:85.32,  scene:"workshop", palette:"dusk",   transition:{type:"ink",    dur:1.4}, lyric:{mode:"lower",    size:72}, params:{},
      note:"刨花随鼓点卷起；每一拍装上一根辐条，木轮逐渐完整" },
    { id:"s08", name:"主歌二 · 木轮单车", start:97.3,   scene:"bicycle",  palette:"paper",  transition:{type:"wheel",  dur:0.9}, lyric:{mode:"upper",    size:68}, params:{},
      note:"线描单车逐笔画出，两只轮子就是前面削好的木轮；『木轮悠悠』车动起来；『朴素人家』小屋炊烟；『握住双手』她坐上后座" },
    { id:"s09", name:"后副歌 · 后座笑靥", start:113.48, scene:"ride",     palette:"spring", transition:{type:"wheel",  dur:0.6}, lyric:{mode:"upper",    size:76}, params:{},
      note:"车轮转速锁定节拍；『年华』落叶向后飘；『笑靥如花』她身边花朵绽放" },
    { id:"s10", name:"桥段 · 星夜辙痕",   start:122.2,  scene:"night",    palette:"night",  transition:{type:"ink",    dur:2.2}, lyric:{mode:"upper",    size:70}, params:{},
      note:"月亮里隐着木轮辐条；『辙痕』地面两道车辙被画出；『星子』流星落下；结尾天色泛白" },
    { id:"s11", name:"副歌二 · 晨光",     start:140.6,  scene:"spring",   palette:"dawn",   transition:{type:"wheel",  dur:0.7}, lyric:{mode:"center",   size:96}, params:{intensity:1.3, sun:true},
      note:"同副歌一，但换成日出配色，木轮背后升起太阳，花瓣更密" },
    { id:"s12", name:"尾声 · 一枚印",     start:155.6,  scene:"seal",     palette:"paper",  transition:{type:"ink",    dur:1.6}, lyric:{mode:"vertical", size:96}, params:{},
      note:"留白山水，竖排歌词；『木生遇淑柔』朱红木轮印章盖下" },
    { id:"s13", name:"尾奏 · 远行",       start:171.0,  scene:"ending",   palette:"dusk",   transition:{type:"ink",    dur:1.5}, lyric:{mode:"lower",    size:72}, params:{},
      note:"两人骑车驶向夕阳（2:54–3:03 有未标注的人声）；最后木轮停下，片名浮现" }
  ]
};
