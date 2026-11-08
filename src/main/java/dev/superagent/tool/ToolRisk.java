package dev.superagent.tool;

/** 工具的风险等级：执行入口据此决定审批与是否允许自动调用。 */
public enum ToolRisk {
    READ_ONLY,
    MUTATING,
    EXECUTES_COMMAND,
    INTERACTIVE
}
