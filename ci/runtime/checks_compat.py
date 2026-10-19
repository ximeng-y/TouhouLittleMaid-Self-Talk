"""CI2 可选依赖 profile 的独立断言。

本模块只注册 optional profile 的新检查，不覆盖 checks.py 中已有的基础检查名。
主 Runner 集成时导入本模块即可让装有 Cloth Config / Curios 的重启套件使用同一
ScenarioEnv；游戏动作通过主 Agent 分发到 CiOptionalCompat。
"""

from __future__ import annotations

from typing import Any

from checks import (
    assert_no_mixin_errors,
    assert_processes_alive,
    baseline,
    check,
    llm_all_text,
    reset_maids,
    spawn_maid,
)


def _optional_action(env, role: str, operation: str, **params: Any) -> dict[str, Any]:
    params["operation"] = operation
    if role == "server":
        return env.server("optional_compat", timeout=60, **params)
    return env.client(role, "optional_compat", timeout=60, **params)


def _wait_env_mode(env, key: str) -> dict[str, Any]:
    return env.wait_until(
        lambda: (
            settings
            if (settings := env.settings()).get("envModes", {}).get(key) == "always"
            else None
        ),
        20,
        f"environment context mode {key} enabled",
    )


def _contains_item(body: str, item: dict[str, Any]) -> bool:
    candidates = [str(item.get("itemName", "")), str(item.get("itemId", ""))]
    return any(value and value.casefold() in body.casefold() for value in candidates)


def _contains_empty_context(body: str) -> bool:
    markers = (
        "没有佩戴饰品",
        "没有饰品",
        "no accessories",
        "no curios",
        "curios functional slots",
    )
    lowered = body.casefold()
    return any(marker.casefold() in lowered for marker in markers)


@check("optional_compat_installed")
def optional_compat_installed(env) -> None:
    """验证可选依赖实际加载、配置入口可打开，以及两类 Curios 上下文的正负路径。"""
    reset_maids(env)
    baseline(env, eventContextEnabled=True)

    server = env.query_server()
    mods = set(server.get("mods", []))
    env.require("optional install has TLM", "touhou_little_maid" in mods, str(sorted(mods)))
    env.require("optional install has cloth-config", "cloth_config" in mods, str(sorted(mods)))
    env.require("optional install has curios", "curios" in mods, str(sorted(mods)))

    config = _optional_action(env, "owner", "open_config")
    env.require("Cloth Config screen opened", config.get("screenOpen") is True, str(config))
    env.require(
        "Self-Talk Cloth Config registration loaded",
        config.get("registrationClassLoaded") is True,
        str(config),
    )
    env.require(
        "Self-Talk config entry visible",
        config.get("entryVisible") is True,
        str(config),
    )
    env.client("owner", "close_screen", timeout=30)

    maid = spawn_maid(env, name="OptionalCompatAlice", chatLanguage="zh_cn")
    for key in ("maid_self_talk_maid_curios", "maid_self_talk_user_curios"):
        env.client(
            "owner",
            "send_payload",
            timeout=30,
            maidId=maid["id"],
            kind="env",
            key=key,
            mode="always",
        )
        _wait_env_mode(env, key)

    _optional_action(
        env,
        "server",
        "clear_curios",
        target="owner",
        player="OwnerBot",
    )
    _optional_action(
        env,
        "server",
        "clear_curios",
        target="maid",
        maidId=maid["id"],
    )
    owner_item = _optional_action(
        env,
        "server",
        "set_curios",
        target="owner",
        player="OwnerBot",
        item="minecraft:diamond",
        count=1,
    )
    maid_item = _optional_action(
        env,
        "server",
        "set_curios",
        target="maid",
        maidId=maid["id"],
        item="minecraft:emerald",
        count=1,
    )
    env.require("owner Curios item installed", owner_item.get("functionalCount", 0) >= 1,
                str(owner_item))
    env.require("maid Curios item installed", maid_item.get("functionalCount", 0) >= 1,
                str(maid_item))

    env.mock.configure(content="可选饰品有物品回答。---可选饰品有物品回答。")
    mark = env.mock.mark()
    env.client("owner", "chat", timeout=60, maidId=maid["id"],
               message="检查主人和女仆的饰品上下文", language="zh_cn")
    requests = env.wait_llm(1, since=mark, timeout=45)
    with_items = llm_all_text(requests[0])
    env.require("owner Curios context contains item", _contains_item(with_items, owner_item),
                with_items[-1200:])
    env.require("maid Curios context contains item", _contains_item(with_items, maid_item),
                with_items[-1200:])

    owner_empty = _optional_action(
        env,
        "server",
        "clear_curios",
        target="owner",
        player="OwnerBot",
    )
    maid_empty = _optional_action(
        env,
        "server",
        "clear_curios",
        target="maid",
        maidId=maid["id"],
    )
    env.require("owner Curios empty snapshot", owner_empty.get("functionalCount") == 0,
                str(owner_empty))
    env.require("maid Curios empty snapshot", maid_empty.get("functionalCount") == 0,
                str(maid_empty))

    env.mock.configure(content="可选饰品无物品回答。---可选饰品无物品回答。")
    mark = env.mock.mark()
    env.client("owner", "chat", timeout=60, maidId=maid["id"],
               message="检查空饰品上下文", language="zh_cn")
    requests = env.wait_llm(1, since=mark, timeout=45)
    without_items = llm_all_text(requests[0])
    env.require("empty Curios context is explicit", _contains_empty_context(without_items),
                without_items[-1200:])

    assert_no_mixin_errors(env)
    assert_processes_alive(env)
    env.record("mods", sorted(mods))
    env.record("config", config)
    env.record("withItemsRequest", with_items[-1200:])
    env.record("withoutItemsRequest", without_items[-1200:])
    env.record("maidId", maid["id"])
