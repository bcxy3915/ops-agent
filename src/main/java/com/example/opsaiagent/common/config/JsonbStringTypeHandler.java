package com.example.opsaiagent.common.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/** MySQL JSON 字符串处理器；保留原类名以兼容已有实体映射。 */
public class JsonbStringTypeHandler extends BaseTypeHandler<String> {

    /**
     * 绑定 JSON 字符串。
     * @param ps 预编译语句
     * @param i 参数位置
     * @param parameter JSON 内容
     * @param jdbcType JDBC 类型
     * @return 无返回值
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, parameter);
    }

    /**
     * 读取 JSON 字符串。
     * @param rs 结果集
     * @param columnName 列名
     * @return JSON 内容，可为空
     */
    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    /**
     * 读取 JSON 字符串。
     * @param rs 结果集
     * @param columnIndex 列序号
     * @return JSON 内容，可为空
     */
    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    /**
     * 读取 JSON 字符串。
     * @param cs 存储过程语句
     * @param columnIndex 列序号
     * @return JSON 内容，可为空
     */
    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
