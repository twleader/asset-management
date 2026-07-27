package com.steven.assets.externalmaterials.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EditorialNewsFilter} 編輯政策判定（Task 199）。案例取自 1561 則真實爬取標題與使用者指定政策，
 * 涵蓋五條規則與其邊界（財經優先、中國政權 vs 中國社會、地緣政治、台灣地方縣市白名單、非財經一般新聞）。
 */
class EditorialNewsFilterTest {

    private static void assertKeep(String title) {
        assertThat(EditorialNewsFilter.keep(title))
                .as("應保留：%s（trace=%s）", title, EditorialNewsFilter.trace(title))
                .isTrue();
    }

    private static void assertDrop(String title) {
        assertThat(EditorialNewsFilter.keep(title))
                .as("應濾除：%s（trace=%s）", title, EditorialNewsFilter.trace(title))
                .isFalse();
    }

    @Nested
    @DisplayName("規則1：財經一律保留（即使提到非白名單縣市）")
    class Finance {
        @Test void 上市櫃財經() {
            assertKeep("台積電加碼投資美國千億美元 林佳龍：台美共創AI盛世最佳時機");
            assertKeep("宏碁AI應用持續擴大 Q2營收852億元近13年同期新高");
            assertKeep("外資續匯出！新台幣連3黑收32.249元 創近15個月新低");
            assertKeep("台積電法說會後ADR盤前崩跌逾4％！拖累台指期夜盤狂瀉逾千點");
        }
        @Test void 股票操作與銀行卡() {
            assertKeep("網通老牌廠大鵬科公開申購今開跑 中籤有望賺5萬元");
            assertKeep("禾伸堂、台燿雙爆違約交割 7月已4檔翻車");
            assertKeep("玉山信用卡6月簽帳1609億 年增224％創新高");
            assertKeep("北富銀獲澳洲營業執照 雪梨分行第3季開業");
            assertKeep("青安3.0利息補貼3+3年 育兒家庭受益金額最多33.75萬");
            assertKeep("昇達科斥資17億 拚低軌衛星市場");
        }
        @Test void 盤勢半導體鋼價房市保險() {   // 對抗式稽核最大宗誤刪，回歸守護
            assertKeep("盤勢分析》量能不足 月線成反壓");
            assertKeep("DRAM 缺口愈補愈大 分析師：2027年供給滿足率恐降至六成");
            assertKeep("臻鼎看 ABF 載板缺貨 二、三年內不易舒緩");
            assertKeep("豐興新盤價 廢鋼跌200元、鋼筋跌300元、型鋼平盤");
            assertKeep("買氣始終打不開！6月北台預售推案陷近8年來單月最低");
            assertKeep("全國59.9萬人囤房 1043大戶擁逾10戶");
            assertKeep("新壽首創重大傷病險結合基因檢測");
            assertKeep("微星：RTX Spark高階款供不應求 首批貨接近完售");
        }
        @Test void 財經提到非白名單縣市仍保留() {
            assertKeep("李長榮先進材料中科廠擴線動土 明年Q3正式供貨");   // 中科=台中，但財經優先
            assertKeep("歐德砸33億進駐沙崙產業園區 國際物流中心落成啟用");
        }
    }

    @Nested
    @DisplayName("規則2：中國新聞——財經/北京政權保留，純社會獵奇濾除")
    class China {
        @Test void 北京政權與中央政治保留() {
            assertKeep("中國前航天局長馬興瑞 嚴重違紀違法被雙開");
            assertKeep("港警搜2獨立書店捕5人 陳培瑜痛批中國政府是背後罪魁禍首");
            assertKeep("反制中國「民族團結法」！美議員提「停止跨境鎮壓法案」");
            assertKeep("歐盟最新安全評估 指中國為「長期戰略挑戰」台海穩定牽動全球安全");
            assertKeep("民眾黨青年團赴中、國台辦未提九二共識！梁文傑曝中共拉攏手法");
        }
        @Test void 中國財經與總經政策保留() {
            assertKeep("中國Q2成長大減速 逾3年新低");
            assertKeep("中國發布「十五五」擴大消費規劃 重點突出「服務消費」");
            assertKeep("中國勞動市場不穩定加劇 30年來首度未設新增就業目標");
            assertKeep("中國試射潛射洲際飛彈 陳冠廷：指向日美及可能介入區域衝突的國家");
        }
        @Test void 中國純社會獵奇濾除() {
            assertDrop("中國男童郵輪如廁慘遭馬桶蓋K中下體 家屬業者互控疏失");
            assertDrop("中國四川巴士驚傳山區墜崖意外 釀6死11傷");
            assertDrop("肚子凸起以為變胖！中國42歲女就醫開刀竟取出50個子宮肌瘤");
            assertDrop("靠爸也沒用！賈平凹之女涉論文抄襲 中國西北大學撤學位解聘");
        }
    }

    @Nested
    @DisplayName("規則3：影響市場的地緣政治（地區＋觸發詞）")
    class Geopolitics {
        @Test void 中東與俄烏戰爭保留() {
            assertKeep("荷姆茲是「紅線」！伊朗嗆襲波灣「所有現存基設」回應美國攻擊");
            assertKeep("距「普廷宮殿」僅24公里 烏克蘭無人艇擊沉俄巡邏艦");
        }
    }

    @Nested
    @DisplayName("規則4：台灣地方新聞——只留北北高")
    class TwLocal {
        @Test void 北北高地方保留() {
            assertKeep("巴威颱風來襲 台北101明天暫停營業一天");
            assertKeep("新北失業勞工子女扶助金即起申請 最高補助3萬5800元");   // 補助＝財經
        }
        @Test void 他縣市地方新聞濾除() {
            assertDrop("民進黨台南市黨部主委就職 黃偉哲喊市長選舉要大贏10萬票以上");
            assertDrop("宜蘭縣長選舉攻防 林國漳指智慧敬老卡比免健保費更多元");
            assertDrop("全聯最大複合店18日在台中開幕　打造一站式生活場域");
            assertDrop("民進黨美女刺客參戰草屯鎮長 張媛婷：回鄉打拚時候到了");   // 鎮長＋南投
            assertDrop("民進黨誰戰竹北市長？綠營擬下週三提名 徵召名單曝光");   // 竹北市長（不得被「北市」子字串誤救）
        }
        @Test void 全國性政治即使在他縣市也保留() {
            assertKeep("無人載具條例審查 顧立雄籲通過政院版：滿足國防迫切需求");
        }
    }

    @Nested
    @DisplayName("規則5：非財經一般新聞濾除（生活/娛樂/消費/社會）")
    class GeneralNews {
        @Test void 生活消費社會濾除() {
            assertDrop("台灣人超愛吃！南韓1水果狂賣5.7億 過半全被台灣「包了」");
            assertDrop("賓士GLB休旅車預售價曝光 入手價200萬有找");
            assertDrop("單人跟團旅行不與領隊配房 東南9月全線實施");
            assertDrop("日本黑熊闖廚房開冰箱！東北熊隻攻擊頻傳引民眾恐慌");
        }
        @Test void 生活軟文不因白名單城市或泛詞而誤留() {
            assertDrop("新北最高 Buffet 宣布熄燈！50樓Café只營業到9月底");           // 新北軟文不救回
            assertDrop("7-ELEVEN AI 拉麵機來了！90秒出餐、半夜也有現煮拉麵");        // AI 泛詞不救回
            assertDrop("兒擠進大企業超有面子！才做1年喊放過我吧 57歲老爸崩潰");       // 大企業泛詞不救回
            assertDrop("中古車爭議創新高！颱風巴威逼近 車商揭泡水車避坑細節");         // 創新高泛詞不救回
        }
        @Test void 全世界體育娛樂一律濾除_城市名不救回() {
            assertDrop("世足》拍照嘲諷梅西曾患「侏儒症」 日本藍髮哥引爆球迷炎上");        // 體育
            assertDrop("土銀羽球隊劉廣珩 許尹鏸勇奪加拿大公開賽銀牌");                  // 銀行球隊仍屬體育
            assertDrop("開啟六感修復新時代！台北沐蘭攜手炫日芬定義城市修復旅宿新維度");   // 台北(city)不救回旅宿軟文
            assertDrop("台灣虎航自購機隊引進全新 Airbus「Airspace」客艙");            // 航空客艙行銷
        }
        // 已知殘留：純軟文若 name-drop 政治人物（如「碧姬馬克宏帶動品牌」經 馬克宏）或含財經詞（如「VIP…高資產客戶」經
        // 資產）仍會被救回——不縮限 POLITY/FINANCE 以免傷政治/財經召回（對抗式稽核之首要與次要關切），屬刻意取捨。
        @Test void 影響股市的財經即使含體育娛樂詞仍保留() {
            assertKeep("凌群電腦Q2營收創高 AI伺服器出貨暢旺");   // 財經優先於體育/娛樂詞
        }
    }

    @Nested
    @DisplayName("財經軼事／都市傳說（Task 221，唯一凌駕 FINANCE 的規則）")
    class Anecdote {
        @Test void 個人理財軼事濾除() {
            assertDrop("68歲退休翁嫌定期定額賺太慢！看到半導體股狂飆就衝了 下場曝光");   // 使用者回報案例
            assertDrop("63歲退休師整天看YouTube影片投資買股 結果退休金少了一半");
            assertDrop("電機系高材生休學跑去炒股  他23歲大賺170％親吐賺爆5秘訣");
            assertDrop("阿公發大財！提早領年金竟賺更多  他67歲靠1招資產衝破1500萬");
            assertDrop("從負債百萬到資產2千萬！40歲的3寶媽靠「1信念」換來人生主導權");
        }
        // 本規則凌駕 FINANCE，誤殺代價最高：以下為必保回歸錨點
        @Test void 含年齡的真政策與人事不可誤殺() {
            assertKeep("青安3.0 申貸限未滿50歲、年收低於200萬");                    // 房貸政策的年齡門檻
            assertKeep("政院明拍板青安3.0方案   年收200萬以下、未滿50歲才可申貸");
            assertKeep("現在適合進場買股票嗎？95歲巴菲特現身、給投資人一句忠告");      // 市場評論
            assertKeep("張忠謀明迎95歲生日！提前收7.5億台積電股息大禮");
        }
        @Test void 不收裸的退休二字以免誤殺退休政策() {
            // 只收 退休翁／退休師 等複合詞；裸的「N歲退休」是退休金政策的標準寫法
            assertDrop("68歲退休翁靠1招賺飽 心路曝光");
        }
        @Test void 無年齡者本規則不發言() {
            // 爆炸半徑為零：不含「N歲」即使有釣魚詞也不由本規則處理
            assertKeep("台積電法說會財測曝光 上調全年營收展望");
        }
    }

    @Nested
    @DisplayName("社會獵奇／犯罪獄政／榮典（Task 221）")
    class SocialOddity {
        @Test void 獄政與獵奇動物濾除() {
            assertDrop("防囚犯越獄！以色列修法 鱷魚可部署監獄周邊");   // 使用者回報案例
            assertDrop("總統令：追晉空軍上尉辛柏毅為空軍少校");
        }
        // 刻意保留 GEO_TRIGGER 的「部署」：移除會誤殺下列真地緣政治
        @Test void 真地緣政治的部署不可誤殺() {
            assertKeep("以色列部署鐵穹攔截伊朗飛彈");
            assertKeep("南韓同意部署薩德系統");
            assertKeep("伊朗在荷姆茲海峽部署新型快艇");
        }
        // 以下詞經語料實測會誤殺，刻意未納入否決集
        @Test void 未納入的司法與社會詞不得誤殺財經政治() {
            assertKeep("台北市六月住宅領照量創近年新低");                     // 領照＝建照使照，營建房市指標
            assertKeep("馬斯克旗下自駕計程車連環車禍 遭美國監理機關調查");     // 車禍＋監理調查驅動個股
            assertKeep("立法院爆發推擠 藍白強行三讀財劃法修正案");            // 推擠＝法案闖關標準寫法
            assertKeep("販售批判習近平書籍 香港書店店員交保");                // 交保＝中國人權
        }
    }

    @Nested
    @DisplayName("南海小型海上摩擦（Task 229）")
    class SouthChinaSeaSkirmish {
        @Test void 南海低烈度摩擦濾除() {
            assertDrop("中國海警南海持棍傷人 菲律賓海軍1人遭打傷");   // 使用者回報案例
            assertDrop("中菲南海對峙 海警噴水驅離菲補給船");
            assertDrop("仁愛礁再起衝突 中國海警登檢並扣押菲漁船");     // 熱點礁名觸發、衝突為中性詞不救不殺
            assertDrop("黃岩島風波 中菲海警船擦撞互指責任");
        }
        // 南海重大事件（仲裁／部署／升級至開火）具市場或地緣意義，不得誤殺
        @Test void 南海重大事件與政治不誤殺() {
            assertKeep("14國聯署挺南海仲裁 南韓缺席遭韓媒酸「看中國眼色」");   // 語料實例
            assertKeep("嚇阻中國 美海防隊艦艇加入南海巡弋");                   // 語料實例：巡弋刻意排除
            assertKeep("美調6艘海防隊巡邏艦前進星、菲 對抗中國在台海、南海灰色侵襲"); // 語料實例：對抗/侵襲/灰色刻意排除
            assertKeep("南海對峙升級 中菲軍艦開火互射示警");                   // 開火＝SCS_ESCALATION 守門救回
            assertKeep("南海封鎖衝擊全球航運 國際油價飆漲");                   // 封鎖＋航運/油價 → 規則①KEEP:finance
        }
        // 中南海＝中共領導層駐地，非南海(South China Sea)；金門摩擦屬台海戰區、非南海
        @Test void 中南海與金門不誤殺() {
            assertKeep("揭GDP真相觸怒中南海！ 習近平令蔡奇動手");             // 語料實例：中南海子字串＋GDP財經
            assertKeep("中國公務船夜闖金門海域 海巡漏夜併航監控、強勢驅離");     // 語料實例：金門非南海
            assertKeep("巴威剛走！中國4艘海警船又闖金門限制水域 海巡強勢驅離");   // 語料實例：金門非南海
        }
        // 低烈度摩擦詞出現在非南海情境時，本規則不得誤觸（AND 守門靠南海地區詞）
        @Test void 摩擦詞在非南海情境不誤觸() {
            assertKeep("荷姆茲因美伊對峙實質封鎖 船東坦言陷入最壞情勢");       // 語料實例：對峙＋荷姆茲＝油運咽喉地緣政治
            assertKeep("中聯致癌油品案 四家公司不動產遭扣押");                 // 語料實例：扣押＋不動產＝財經
        }
    }

    @Nested
    @DisplayName("發票／彩券中獎與家事遺產（Task 240）")
    class LotteryAndEstate {
        @Test void 發票彩券中獎濾除() {
            assertDrop("7-11開出千萬中獎發票　花150元買飲品成幸運兒");            // 使用者回報案例
            assertDrop("只花10元抱回200萬！7-ELEVEN 開出一張千萬、七張百萬發票");  // 發票∧開出 AND 組合
            assertDrop("大樂透頭獎連17摃  加碼100萬獎只剩7組");                   // 語料實例：加碼＝FINANCE
            assertDrop("快對發票！5-6月統一發票千萬獎「38548029」 完整獎號在這裡");
            assertDrop("最好的生日禮物！美國男買刮刮樂爽中3千萬");
        }
        // 金額詞 千萬／加碼 是真財經的常見訊號，不得因本規則而誤殺
        @Test void 金額詞的真財經不誤殺() {
            assertKeep("金管會上半年裁罰出爐 銀行業罰鍰較去年增加近兩千萬");
            assertKeep("＜財經週報-青安3.0＞青安3.0千萬額度不夠用？ 全台16縣市平均房貸不到千萬");
            assertKeep("影／混凝土大廠永固-KY董座砸9千萬炒股護盤 一家三人涉證交法送辦");
            assertKeep("AI巨頭財報前瞻一表看！微軟、Meta、蘋果下周開獎 聚焦資本支出");  // 開獎刻意排除
            assertKeep("台積電發放特別獎金 每人平均逾百萬");                            // 特別獎刻意排除（特別獎金）
        }
        @Test void 家事遺產糾紛濾除() {
            assertDrop("很多家庭急著分遺產 忘了另一位父母還活著");                  // 使用者回報案例（標題）
            assertDrop("他繼承父親1500萬遺產全丟進股市 慘痛代價曝光了");
            assertDrop("最受寵卻一毛都沒分到！阿公留4千萬遺產「被獨漏」 長孫看完遺囑傻眼了");
            assertDrop("孫女獲祖父母數百萬美元遺產 被要求發誓保密  父因「妻顧人怨」只能拿數萬美元");
        }
        // 法制／稅制／市場主體三類豁免
        @Test void 遺產的法制稅制與市場主體不誤殺() {
            assertKeep("遺產可免分兄弟姊妹？立院朝野拍板 特留分修法7/28處理");
            assertKeep("被繼承人遺有應收股利  遺產稅申報一次看");
            assertKeep("台積電配息創新高、繼承股票先別high！國稅局爆「這天」成股利報稅分水嶺");
            assertKeep("三星搶攻AI晶片商機！將龍仁半導體國家產業園區首座晶圓廠量產提前一年"); // 家產刻意排除
        }
        // 「集團」刻意不列入 ESTATE_EXEMPT：它是高頻泛詞且同時在 FINANCE，收了會讓此類落回 KEEP:finance
        @Test void 詐騙集團奪產仍應濾除() {
            assertDrop("詐騙集團騙走老翁遺產 檢警偵辦中");
        }
    }

    @Nested
    @DisplayName("六都軟文與純財經來源（Task 240）")
    class CivicSoftAndFinanceFeed {
        @Test void 六都純軟性行程濾除() {
            assertDrop("侯友宜、谷立言、片山和之同遊新北  搭船欣賞淡江大橋");        // 使用者回報案例
            assertDrop("（新北）蘇巧慧陪小朋友開心玩 李四川參拜宮廟");
            assertDrop("李四川現身動畫路跑 盼打造新北IP活動城市 釣出蔡詩萍留言");
            assertDrop("高雄佛光山修行》蔡壁如宣布出家 預告此時再相見");
            assertDrop("坪林川友會授旗 李四川：強化交通帶動新北茶鄉觀光升級");
        }
        // 只有財經（規則①）豁免；強政治（POLITY_STRONG，規則⑤）在 LIFESTYLE（規則②）之後，不豁免
        @Test void 六都的財經不誤殺() {
            assertKeep("全球最大AI晶片先進封裝廠　台積電嘉義二期動土前7座宮廟遶境祈福　員工、在地股東以信徒身分同行");
            assertKeep("新北大巨蛋落腳樹林！蘇巧慧謝侯友宜、盼加速完善當地交通建設");
            assertKeep("高雄市待售新成屋逼近1.6萬宅  楠梓和鳳山最多");
        }
        // 「參拜」刻意不收（只收「宮廟」）：裸「參拜」會在規則②攔下靖國神社類中日關係事件，
        // 而規則③CHINA／⑤POLITY_STRONG 都在其後、無從救回
        @Test void 參拜靖國神社類不誤殺() {
            assertKeep("日相高市早苗參拜靖國神社 中國外交部強烈抗議");
            assertKeep("習近平參拜毛澤東紀念堂 中共高層全數到齊");
        }
        // 陽明＝陽明海運，不得誤中陽明交大；運價為配套（唯一公司訊號為「陽明」的航運標題靠它保住）
        @Test void 陽明子字串不誤中() {
            assertDrop("陽明交大教評會爆爭議 教育部長：組成有瑕疵");                // 使用者回報案例
            assertKeep("運價走揚  陽明6月營收165.91億年月雙增");
            assertKeep("美新關稅敲定後 陽明：運價後市將明朗");
            assertKeep("陽明蔡豐明：運價雖跌 貨量仍滿 後市視美關稅政策而定");        // 語料實例：須用完整標題
            assertKeep("陽明、台驊6月營運亮麗");
            assertKeep("陽明7月運價走弱 貨量持平");                                // 運價為唯一財經訊號
            assertKeep("SCFI運價指數周跌4.3%");
            assertKeep("鴻海研究院聯手陽明交大 研發超大容量矽光子技術");
        }
        // 純財經來源只套三條凌駕 FINANCE 的否決集，其餘一律保留
        @Test void 純財經來源只濾三類雜訊() {
            assertThat(EditorialNewsFilter.traceFinanceFeed("很多家庭急著分遺產 忘了另一位父母還活著 當父親或母親其中一位離世後，多數家庭討論的第一件事，往往不是照顧，而是繼承，房子要不要賣？存款怎麼分？"))
                    .startsWith("DROP");   // 使用者回報案例：標題＋摘要（摘要含「存款」會在整套 cascade 被 KEEP:finance 救回）
            assertThat(EditorialNewsFilter.traceFinanceFeed("完整獎號一次看！5、6月統一發票開獎千萬特別獎「38548029」")).startsWith("DROP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("68歲退休翁嫌定期定額賺太慢！看到半導體股狂飆就衝了 下場曝光")).startsWith("DROP");
            // 以下在整套 cascade 會被誤殺，故純財經來源刻意不套 LIFESTYLE／SOCIAL_ODDITY／non-finance-general
            assertThat(EditorialNewsFilter.traceFinanceFeed("旅遊市況熱 雄獅東北亞賞楓行程銷售破5成 將擴大拓郵輪版圖")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("減肥藥大戰打進法院！諾和諾德怒告禮來廣告誤導")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("Hugging Face遭OpenAI模型沙盒「越獄」攻擊 中國AI工具GLM-5.2臨危救場")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("自駕合作恐生變 Waymo傳2028年後終止合作 Uber跌逾4%")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("基本工資調漲至3萬 商總：對缺工問題仍無解、對雇外勞企業受衝擊最大")).startsWith("KEEP");
        }
    }
}
