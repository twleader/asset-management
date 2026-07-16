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
    }
}
