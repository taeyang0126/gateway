---
inclusion: fileMatch
fileMatchPattern: ".kiro/specs/**"
---

## Spec 粒度检查
requirements 完成后，根据以下信号评估是否需要拆分：
- 需求之间无代码依赖（改动不涉及相同文件）→ 拆
- 预估改动文件超过 25 个 → 拆
- 预估 task 超过 8 个 → 拆
- 功能描述需要用"和"连接两个独立概念 → 拆
如果建议拆分，给出拆分方案并等待用户确认后再继续。
