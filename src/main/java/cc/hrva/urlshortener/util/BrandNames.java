package cc.hrva.urlshortener.util;

import java.util.List;
import java.util.Set;

public final class BrandNames {

    public static final List<String> ALL = List.of("njuskalo", "jofogas", "paypal", "revolut", "apple", "icloud", "microsoft", "outlook",
            "google", "gmail", "facebook", "instagram", "whatsapp", "netflix", "amazon", "booking", "airbnb", "vinted", "ebay", "olx", "dhl",
            "coinbase", "binance");
    public static final Set<String> COMMON_WORDS = Set.of("booking", "outlook");

    private BrandNames() {
    }

}
