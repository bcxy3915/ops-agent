package com.example.opsaiagent.sensitive;

import lombok.Getter;

/**
 * 敏感信息类型
 * 每种类型对应一种脱敏策略，新增类型时在 mask 方法里加一个分支即可。
 */
@Getter
public enum SensitiveType {

    /**
     * 密码：全部替换为 *
     */
    PASSWORD("password", "passwd", "pwd", "secret", "token", "accesstoken", "refreshtoken", "authorization", "apikey"),

    /**
     * 手机号：保留前 3 位和后 4 位
     */
    PHONE("phone", "mobile", "telephone", "tel"),

    /**
     * 邮箱：保留首字母和 @ 后的域名
     */
    EMAIL("email", "mail", "e-mail"),

    /**
     * 身份证：保留前 3 位和后 4 位
     */
    ID_CARD("idcard", "idno", "idnumber", "identitycard"),

    /**
     * 银行卡：保留前 4 位和后 4 位
     */
    BANK_CARD("bankcard", "cardno", "creditcard"),

    /**
     * 姓名：保留姓氏，其余用 *
     */
    NAME("realname", "fullname", "truename");

    /**
     * 匹配的字段名（不区分大小写）
     */
    private final String[] fieldNames;

    SensitiveType(String... fieldNames) {
        this.fieldNames = fieldNames;
    }
}