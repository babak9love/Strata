package dev.cluvex.strata.core.donate

data class Wallet(val id: String, val network: String, val address: String)

object Donation {
    val wallets = listOf(
        Wallet("tron", "TRX / USDT · Tron (TRC-20)", "TRxVSHcoADZnBfztFmFb2TQopusAwWYEVR"),
        Wallet("bnb", "USDT · BNB Smart Chain (BEP-20)", "0x20c980AEF36Aa71D0A0e7bE382AeD20b73bC6D27"),
        Wallet("ton", "TON (Gram)", "UQAH75bXaaRUhZMwiF0ZujOXFDDmvLSPASKoOsWF0HNasiaM"),
    )
}
