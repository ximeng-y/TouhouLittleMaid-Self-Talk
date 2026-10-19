"""控制器与场景断言共享的异常类型。"""


class RunnerError(RuntimeError):
    """启动、环境或控制通道错误。"""


class CheckFailure(AssertionError):
    """业务断言失败。"""
