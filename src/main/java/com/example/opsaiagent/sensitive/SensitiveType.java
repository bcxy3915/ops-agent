package com.example.opsaiagent.sensitive;

/**
 * 敏感信息类型
 * 每种类型对应一种脱敏策略，新增类型时在 mask 方法里加一个分支即可。
 */
public enum SensitiveType {

    /** 密码：全部替换为 * */
    PASSWORD,

    /** 手机号：保留前 3 位和后 4 位 */
    PHONE,

    /** 邮箱：保留首字母和 @ 后的域名 */
    EMAIL,

    /** 身份证：保留前 3 位和后 4 位 */
    ID_CARD,

    /** 银行卡：保留前 4 位和后 4 位 */
    BANK_CARD,

    /** 姓名：保留姓氏，其余用 * */
    NAME
}