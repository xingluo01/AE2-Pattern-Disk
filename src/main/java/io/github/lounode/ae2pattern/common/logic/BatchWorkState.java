package io.github.lounode.ae2pattern.common.logic;

/**
 * 批处理装配机对外报告的工作状态。
 *
 * <p>存在的理由：以前这些情况都被折成一句「没在干活」，而它们要玩家做的事完全不同——没接网络、没装元件、
 * 磁盘里没有能跑的样板、在等材料、材料不够、没电、余料算不出来。从外面看它们无法区分，于是「不肯干活」与
 * 「在等送货」长得一模一样，任何显示都说不清眼前是哪一种。</p>
 */
public enum BatchWorkState {
    /** The machine is not on the grid, so nothing it reports about itself is current. */
    OFFLINE,
    /** No storage cell to work out of, so the crafting CPU never hands it anything. */
    NO_BUFFER,
    /** The disks hold no pattern this machine can run, so a job could never be matched to one. */
    NO_PATTERN,
    /** Nothing queued and nothing left to return. */
    IDLE,
    /** Jobs are queued, still inside the quiet window that closes before a batch runs. */
    WAITING_FOR_MATERIAL,
    /** A queued pattern produced no execution plan, so its job can never run. */
    UNRESOLVABLE_PATTERN,
    /** The cell buffer could not supply the inputs for the pattern being run. */
    INPUTS_UNAVAILABLE,
    /** The grid could not pay for the run. */
    NO_POWER,
    /** A container remainder could not be worked out, so the run was rolled back before producing anything. */
    REMAINDER_FAILED,
    /** The queue is done; produced outputs are still returning to the network. */
    RETURNING_OUTPUTS,
    /** Outputs are waiting but the network is taking none of them, so they will not drain on their own. */
    OUTPUT_BLOCKED,
    /** A run completed on the last attempt. */
    WORKING
}
