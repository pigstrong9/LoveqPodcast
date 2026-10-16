# Add project specific ProGuard rules here.

# ---- 官网 HTML 解析（Jsoup）----
# Jsoup 是普通 jar，不随包提供 consumer 规则；虽然本项目是直接调用（没有反射），
# 但它内部结构复杂、又无法在模拟器上跑一遍冒烟，保留其全部类最省心。
# 代价只有几百 KB，换来「解析永远不会因为更名/裁剪而悄悄坏掉」。
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# 缺失的可选注解（jsr305）在 R8 里只是警告，不必让它刷屏
-dontwarn javax.annotation.**
