package com.myfunds.core

/** Edit identity/order only; holdings, costs and unrelated ledger entries stay intact. */
object PortfolioEditing {
    private fun scope(p: Portfolio, groupId: String?): List<Holding> {
        require(groupId == null || p.groups.any { it.id == groupId }) { "找不到该分组" }
        return p.holdings.filter { groupId == null || it.groupId == groupId }.sortedBy { it.order }
    }
    fun reorder(p: Portfolio, orderedIds: List<String>, groupId: String? = null): Portfolio {
        p.validate()
        val members = scope(p, groupId)
        val memberIds = members.map { it.id }.toSet()
        require(orderedIds.size == members.size && orderedIds.toSet() == memberIds) { "排序条目有遗漏或重复，请重新编辑" }
        val byId = p.holdings.associateBy { it.id }
        val iterator = orderedIds.iterator()
        val ordered = p.holdings.sortedBy { it.order }.map { if (it.id in memberIds) byId.getValue(iterator.next()) else it }
        return p.copy(holdings = ordered.mapIndexed { index, h -> h.copy(order = index) }, settings = p.settings.copy(sort = "custom")).validate()
    }
    fun move(p: Portfolio, id: String, targetIndex: Int, groupId: String? = null): Portfolio {
        val ids = scope(p, groupId).map { it.id }.toMutableList()
        val index = ids.indexOf(id)
        require(index >= 0 && targetIndex in ids.indices) { "无法移动到该位置" }
        ids.add(targetIndex, ids.removeAt(index))
        return reorder(p, ids, groupId)
    }
    fun delete(p: Portfolio, ids: Set<String>): Portfolio {
        p.validate()
        require(ids.isNotEmpty() && p.holdings.map { it.id }.toSet().containsAll(ids)) { "请选择现有基金后再删除" }
        return p.copy(holdings = p.holdings.filterNot { it.id in ids }.sortedBy { it.order }.mapIndexed { i, h -> h.copy(order = i) }, trades = p.trades.filterNot { it.holdingId in ids }).validate()
    }
}
