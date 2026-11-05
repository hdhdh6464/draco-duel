# 時光器對決 Duel of the Time-Turner

馬爾福 vs 佛地魔的子彈時間決鬥遊戲（Java Swing）。佛地魔每次施法前，時間會停住，你在這段時間排好接下來的動作，再按 Enter 讓它們上演。

## 模式
- **單人**：對戰 AI 佛地魔
- **新手教程**：照著步驟學操作，教學中不會倒下
- **P2 佛地魔**：第二位玩家操控佛地魔，只有巫師一方有子彈時間
- **PvP**：兩位巫師輪流規劃回合

## 執行
- 已裝 Java 8 以上：點兩下 `run.bat`，或執行 `java -jar DracoDuel.jar`
- 自己編譯：把 JDK 放在 `jdk/` 資料夾，再執行 `build.bat`
- 跑測試：`run-tests.bat`
- 打包給沒裝 Java 的人：`build-solo.bat`，需要 JDK 14 以上（要用 jpackage），會產生 `dist-solo/DracoDuel-Solo.zip`

## 按鍵
| 按鍵 | 功能 |
|---|---|
| W A S D / 滑鼠左鍵 | 移動 |
| Q / E / Z / C | 翻滾 / 側閃 / 後空翻 / 幻影移形 |
| 1 2 3 4 | 暴牙咒 / 繳械咒 / 昏擊咒 / 召蛇咒 |
| F G R T | 跳踢 / 連拳 / 迴旋踢 / 過肩摔 |
| 5 | 鐵甲咒 |
| Enter / Backspace / Delete | 開始演出 / 復原 / 清空 |
| H | 招式說明 |
