/*
 * PowerAuth Server and related software components
 * Copyright (C) 2018 Wultra s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.getlime.security.powerauth.app.server.service.behavior.tasks;

import com.google.common.io.BaseEncoding;
import io.getlime.security.powerauth.GetNonPersonalizedEncryptionKeyResponse;
import io.getlime.security.powerauth.GetPersonalizedEncryptionKeyResponse;
import io.getlime.security.powerauth.app.server.converter.ServerPrivateKeyConverter;
import io.getlime.security.powerauth.app.server.database.RepositoryCatalogue;
import io.getlime.security.powerauth.app.server.database.model.ActivationStatus;
import io.getlime.security.powerauth.app.server.database.model.KeyEncryptionMode;
import io.getlime.security.powerauth.app.server.database.model.entity.ActivationRecordEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.ApplicationVersionEntity;
import io.getlime.security.powerauth.app.server.database.model.entity.MasterKeyPairEntity;
import io.getlime.security.powerauth.app.server.database.repository.ActivationRepository;
import io.getlime.security.powerauth.app.server.database.repository.ApplicationVersionRepository;
import io.getlime.security.powerauth.app.server.database.repository.MasterKeyPairRepository;
import io.getlime.security.powerauth.app.server.service.i18n.LocalizationProvider;
import io.getlime.security.powerauth.app.server.service.model.ServiceError;
import io.getlime.security.powerauth.crypto.lib.generator.KeyGenerator;
import io.getlime.security.powerauth.crypto.lib.util.HMACHashUtilities;
import io.getlime.security.powerauth.crypto.server.keyfactory.PowerAuthServerKeyFactory;
import io.getlime.security.powerauth.provider.CryptoProviderUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * Behavior class implementing the end-to-end encryption related processes. The
 * class separates the logic from the main service class.
 *
 * @author Petr Dvorak, petr@wultra.com
 */
@Component
public class EncryptionServiceBehavior {

    private RepositoryCatalogue repositoryCatalogue;

    private LocalizationProvider localizationProvider;

    private final PowerAuthServerKeyFactory powerAuthServerKeyFactory = new PowerAuthServerKeyFactory();

    private ServerPrivateKeyConverter serverPrivateKeyConverter;

    @Autowired
    public EncryptionServiceBehavior(RepositoryCatalogue repositoryCatalogue) {
        this.repositoryCatalogue = repositoryCatalogue;
    }

    @Autowired
    public void setLocalizationProvider(LocalizationProvider localizationProvider) {
        this.localizationProvider = localizationProvider;
    }

    @Autowired
    public void setServerPrivateKeyConverter(ServerPrivateKeyConverter serverPrivateKeyConverter) {
        this.serverPrivateKeyConverter = serverPrivateKeyConverter;
    }

    /**
     * This method generates a derived transport key for the purpose of end-to-end encryption.
     * The response contains a derived key and index used to deduce it.
     * @param activationId Activation that is supposed to use encryption key.
     * @param sessionIndex Optional session index.
     * @param keyConversionUtilities Key conversion utility class.
     * @return Response with a generated encryption key details.
     * @throws Exception In activation with given ID was not found or other business logic error.
     */
    public GetPersonalizedEncryptionKeyResponse generateEncryptionKeyForActivation(String activationId, String sessionIndex, CryptoProviderUtil keyConversionUtilities) throws Exception {

        final ActivationRepository activationRepository = repositoryCatalogue.getActivationRepository();
        final ActivationRecordEntity activation = activationRepository.findActivation(activationId);

        // If there is no such activation or activation is not active, return error
        if (activation == null || !ActivationStatus.ACTIVE.equals(activation.getActivationStatus())) {
            throw localizationProvider.buildExceptionForCode(ServiceError.ACTIVATION_NOT_FOUND);
        }

        String devicePublicKeyBase64 = activation.getDevicePublicKeyBase64();

        // Decrypt server private key (depending on encryption mode)
        String serverPrivateKeyFromEntity = activation.getServerPrivateKeyBase64();
        KeyEncryptionMode serverPrivateKeyEncryptionMode = activation.getServerPrivateKeyEncryption();
        String serverPrivateKeyBase64 = serverPrivateKeyConverter.fromDBValue(serverPrivateKeyEncryptionMode, serverPrivateKeyFromEntity, activation.getUserId(), activationId);

        // Convert the keys
        PublicKey devicePublicKey = keyConversionUtilities.convertBytesToPublicKey(BaseEncoding.base64().decode(devicePublicKeyBase64));
        PrivateKey serverPrivateKey = keyConversionUtilities.convertBytesToPrivateKey(BaseEncoding.base64().decode(serverPrivateKeyBase64));

        SecretKey masterKey = powerAuthServerKeyFactory.generateServerMasterSecretKey(serverPrivateKey, devicePublicKey);
        SecretKey masterTransportKey = powerAuthServerKeyFactory.generateServerTransportKey(masterKey);
        byte[] masterTransportKeyData = keyConversionUtilities.convertSharedSecretKeyToBytes(masterTransportKey);

        KeyGenerator keyGenerator = new KeyGenerator();

        // Use provided index or generate own, if not provided.
        byte[] sessionIndexBytes = null;
        if (sessionIndex != null) {
            sessionIndexBytes = BaseEncoding.base64().decode(sessionIndex);
        }
        byte[] index;
        if (sessionIndexBytes == null || sessionIndexBytes.length != 16) {
            index = keyGenerator.generateRandomBytes(16);
        } else {
            index = sessionIndexBytes;
        }

        byte[] tmpBytes = new HMACHashUtilities().hash(index, masterTransportKeyData);
        byte[] derivedTransportKeyBytes = keyGenerator.convert32Bto16B(tmpBytes);

        String indexBase64 = BaseEncoding.base64().encode(index);
        String derivedTransportKeyBase64 = BaseEncoding.base64().encode(derivedTransportKeyBytes);

        GetPersonalizedEncryptionKeyResponse response = new GetPersonalizedEncryptionKeyResponse();
        response.setActivationId(activation.getActivationId());
        response.setEncryptionKey(derivedTransportKeyBase64);
        response.setEncryptionKeyIndex(indexBase64);
        return response;
    }

    /**
     * This method generates a derived transport key for the purpose of end-to-end encryption.
     * The response contains a derived key and index used to deduce it.
     * @param applicationKey Application that is supposed to use encryption key.
     * @param sessionIndexBase64 Optional base64-encoded session index.
     * @param ephemeralPublicKeyBase64 Base64-encoded ephemeral public key.
     * @param keyConversionUtilities Key conversion utility class.
     * @return Response with a generated encryption key details.
     * @throws Exception In activation with given ID was not found or other business logic error.
     */
    public GetNonPersonalizedEncryptionKeyResponse generateNonPersonalizedEncryptionKeyForApplication(String applicationKey, String sessionIndexBase64, String ephemeralPublicKeyBase64, CryptoProviderUtil keyConversionUtilities) throws Exception {

        final ApplicationVersionRepository applicationVersionRepository = repositoryCatalogue.getApplicationVersionRepository();
        ApplicationVersionEntity applicationVersion = applicationVersionRepository.findByApplicationKey(applicationKey);

        if (applicationVersion == null || !applicationVersion.getSupported()) {
            throw localizationProvider.buildExceptionForCode(ServiceError.NO_APPLICATION_ID);
        }

        final MasterKeyPairRepository masterKeyPairRepository = repositoryCatalogue.getMasterKeyPairRepository();
        MasterKeyPairEntity keypair = masterKeyPairRepository.findFirstByApplicationIdOrderByTimestampCreatedDesc(applicationVersion.getApplication().getId());
        if (keypair == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.NO_MASTER_SERVER_KEYPAIR);
        }

        byte[] ephemeralKeyBytes = BaseEncoding.base64().decode(ephemeralPublicKeyBase64);
        PublicKey ephemeralPublicKey = keyConversionUtilities.convertBytesToPublicKey(ephemeralKeyBytes);
        if (ephemeralPublicKey == null) {
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_KEY_FORMAT);
        }

        String masterPrivateKeyBase64 = keypair.getMasterKeyPrivateBase64();
        PrivateKey masterPrivateKey = keyConversionUtilities.convertBytesToPrivateKey(BaseEncoding.base64().decode(masterPrivateKeyBase64));

        SecretKey masterKey = powerAuthServerKeyFactory.generateServerMasterSecretKey(masterPrivateKey, ephemeralPublicKey);
        byte[] masterTransportKeyData = keyConversionUtilities.convertSharedSecretKeyToBytes(masterKey);

        KeyGenerator keyGenerator = new KeyGenerator();

        // Use provided index or generate own, if not provided.
        byte[] sessionIndexBytes = null;
        if (sessionIndexBase64 != null) {
            sessionIndexBytes = BaseEncoding.base64().decode(sessionIndexBase64);
        }
        byte[] index;
        if (sessionIndexBytes == null || sessionIndexBytes.length != 16) {
            index = keyGenerator.generateRandomBytes(16);
        } else {
            index = sessionIndexBytes;
        }

        byte[] tmpBytes = new HMACHashUtilities().hash(index, masterTransportKeyData);
        byte[] derivedTransportKeyBytes = keyGenerator.convert32Bto16B(tmpBytes);

        String indexBase64 = BaseEncoding.base64().encode(index);
        String derivedTransportKeyBase64 = BaseEncoding.base64().encode(derivedTransportKeyBytes);

        GetNonPersonalizedEncryptionKeyResponse response = new GetNonPersonalizedEncryptionKeyResponse();
        response.setApplicationKey(applicationKey);
        response.setApplicationId(applicationVersion.getApplication().getId());
        response.setEncryptionKey(derivedTransportKeyBase64);
        response.setEncryptionKeyIndex(indexBase64);
        response.setEphemeralPublicKey(ephemeralPublicKeyBase64);
        return response;
    }

}
