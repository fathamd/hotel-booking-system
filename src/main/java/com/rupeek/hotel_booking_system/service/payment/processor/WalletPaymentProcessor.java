package com.rupeek.hotel_booking_system.service.payment.processor;

import com.rupeek.hotel_booking_system.api.dto.payment.PaymentRequest;
import com.rupeek.hotel_booking_system.api.dto.payment.WalletDetailsRequest;
import com.rupeek.hotel_booking_system.domain.enums.PaymentMethod;
import com.rupeek.hotel_booking_system.domain.exception.InvalidPaymentDetailsException;
import com.rupeek.hotel_booking_system.service.payment.gateway.PaymentGateway;
import org.springframework.stereotype.Component;

@Component
public class WalletPaymentProcessor extends GatewayPaymentProcessor<WalletDetailsRequest> {

    private static final String WALLET_ID_PATTERN = "[A-Za-z0-9._@+-]{3,50}";

    public WalletPaymentProcessor(PaymentGateway gateway) {
        super(gateway);
    }

    @Override
    public PaymentMethod supportedMethod() {
        return PaymentMethod.WALLET;
    }

    @Override
    protected WalletDetailsRequest instrumentDetails(PaymentRequest request) {
        return request.wallet();
    }

    @Override
    protected String detailsField() {
        return "wallet";
    }

    @Override
    protected String validateAndDescribe(WalletDetailsRequest wallet) {
        if (wallet.provider() == null) {
            throw new InvalidPaymentDetailsException("Wallet provider is required");
        }
        String walletId = wallet.walletId() == null ? "" : wallet.walletId().strip();
        if (!walletId.matches(WALLET_ID_PATTERN)) {
            throw new InvalidPaymentDetailsException("Wallet id is invalid");
        }
        return wallet.provider() + ":" + walletId;
    }
}
