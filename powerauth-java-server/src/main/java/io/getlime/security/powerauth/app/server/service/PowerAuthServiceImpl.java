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
package io.getlime.security.powerauth.app.server.service;

import io.getlime.security.powerauth.*;
import io.getlime.security.powerauth.app.server.configuration.PowerAuthServiceConfiguration;
import io.getlime.security.powerauth.app.server.converter.XMLGregorianCalendarConverter;
import io.getlime.security.powerauth.app.server.database.model.AdditionalInformation;
import io.getlime.security.powerauth.app.server.service.behavior.ServiceBehaviorCatalogue;
import io.getlime.security.powerauth.app.server.service.exceptions.GenericServiceException;
import io.getlime.security.powerauth.app.server.service.i18n.LocalizationProvider;
import io.getlime.security.powerauth.app.server.service.model.ServiceError;
import io.getlime.security.powerauth.crypto.lib.config.PowerAuthConfiguration;
import io.getlime.security.powerauth.provider.CryptoProviderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.InvalidKeyException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Default implementation of the PowerAuth 2.0 Server service.
 * The implementation of this service is divided into "behaviors"
 * responsible for individual processes.
 *
 * @see io.getlime.security.powerauth.app.server.service.PowerAuthService
 *
 * @author Petr Dvorak, petr@wultra.com
 */
@Component
public class PowerAuthServiceImpl implements PowerAuthService {

    private PowerAuthServiceConfiguration powerAuthServiceConfiguration;

    private ServiceBehaviorCatalogue behavior;

    private LocalizationProvider localizationProvider;

    private BuildProperties buildProperties;

    // Prepare logger
    private static final Logger logger = LoggerFactory.getLogger(PowerAuthServiceImpl.class);

    @Autowired
    public void setPowerAuthServiceConfiguration(PowerAuthServiceConfiguration powerAuthServiceConfiguration) {
        this.powerAuthServiceConfiguration = powerAuthServiceConfiguration;
    }

    @Autowired
    public void setBehavior(ServiceBehaviorCatalogue behavior) {
        this.behavior = behavior;
    }

    @Autowired
    public void setLocalizationProvider(LocalizationProvider localizationProvider) {
        this.localizationProvider = localizationProvider;
    }

    @Autowired(required = false)
    public void setBuildProperties(BuildProperties buildProperties) {
        this.buildProperties = buildProperties;
    }

    private final CryptoProviderUtil keyConversionUtilities = PowerAuthConfiguration.INSTANCE.getKeyConvertor();

    @Override
    public GetSystemStatusResponse getSystemStatus(GetSystemStatusRequest request) throws Exception {
        logger.info("GetSystemStatusRequest received");
        GetSystemStatusResponse response = new GetSystemStatusResponse();
        response.setStatus("OK");
        response.setApplicationName(powerAuthServiceConfiguration.getApplicationName());
        response.setApplicationDisplayName(powerAuthServiceConfiguration.getApplicationDisplayName());
        response.setApplicationEnvironment(powerAuthServiceConfiguration.getApplicationEnvironment());
        if (buildProperties != null) {
            response.setVersion(buildProperties.getVersion());
            response.setBuildTime(XMLGregorianCalendarConverter.convertFrom(Date.from(buildProperties.getTime())));
        }
        response.setTimestamp(XMLGregorianCalendarConverter.convertFrom(new Date()));
        logger.info("GetSystemStatusRequest succeeded");
        return response;
    }

    @Override
    public GetErrorCodeListResponse getErrorCodeList(GetErrorCodeListRequest request) {
        logger.info("GetErrorCodeListRequest received");
        String language = request.getLanguage();
        // Check if the language is valid ISO language, use EN as default
        if (Arrays.binarySearch(Locale.getISOLanguages(), language) < 0) {
            language = Locale.ENGLISH.getLanguage();
        }
        Locale locale = new Locale(language);
        GetErrorCodeListResponse response = new GetErrorCodeListResponse();
        List<String> errorCodeList = ServiceError.allCodes();
        for (String errorCode : errorCodeList) {
            GetErrorCodeListResponse.Errors error = new GetErrorCodeListResponse.Errors();
            error.setCode(errorCode);
            error.setValue(localizationProvider.getLocalizedErrorMessage(errorCode, locale));
            response.getErrors().add(error);
        }
        logger.info("GetErrorCodeListRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public GetActivationListForUserResponse getActivationListForUser(GetActivationListForUserRequest request) throws Exception {
        try {
            String userId = request.getUserId();
            Long applicationId = request.getApplicationId();
            logger.info("GetActivationListForUserRequest received, userId: {}, applicationId: {}", userId, String.valueOf(applicationId));
            GetActivationListForUserResponse response = behavior.getActivationServiceBehavior().getActivationList(applicationId, userId);
            logger.info("GetActivationListForUserRequest succeeded");
            return response;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public GetActivationStatusResponse getActivationStatus(GetActivationStatusRequest request) throws Exception {
        try {
            String activationId = request.getActivationId();
            logger.info("GetActivationStatusRequest received, activationId: {}", activationId);
            GetActivationStatusResponse response = behavior.getActivationServiceBehavior().getActivationStatus(activationId, keyConversionUtilities);
            logger.info("GetActivationStatusResponse succeeded");
            return response;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }

    }

    @Override
    @Transactional
    public InitActivationResponse initActivation(InitActivationRequest request) throws Exception {
        try {
            String userId = request.getUserId();
            Long applicationId = request.getApplicationId();
            Long maxFailedCount = request.getMaxFailureCount();
            Date activationExpireTimestamp = XMLGregorianCalendarConverter.convertTo(request.getTimestampActivationExpire());
            logger.info("InitActivationRequest received, userId: {}, applicationId: {}", userId, String.valueOf(applicationId));
            InitActivationResponse response = behavior.getActivationServiceBehavior().initActivation(applicationId, userId, maxFailedCount, activationExpireTimestamp, keyConversionUtilities);
            logger.info("InitActivationRequest succeeded");
            return response;
        } catch (GenericServiceException ex) {
            logger.error("Unknown error occurred", ex);
            throw ex;
        } catch (InvalidKeySpecException | InvalidKeyException ex) {
            logger.error("Unknown error occurred", ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_KEY_FORMAT);
        }
    }

    @Override
    @Transactional
    public PrepareActivationResponse prepareActivation(PrepareActivationRequest request) throws Exception {
        try {
            // Get request parameters
            String activationIdShort = request.getActivationIdShort();
            String activationNonceBase64 = request.getActivationNonce();
            String cDevicePublicKeyBase64 = request.getEncryptedDevicePublicKey();
            String activationName = request.getActivationName();
            String ephemeralPublicKey = request.getEphemeralPublicKey();
            String applicationKey = request.getApplicationKey();
            String applicationSignature = request.getApplicationSignature();
            String extras = request.getExtras();
            logger.info("PrepareActivationRequest received, activationIdShort: {}", activationIdShort);
            PrepareActivationResponse response = behavior.getActivationServiceBehavior().prepareActivation(activationIdShort, activationNonceBase64, ephemeralPublicKey, cDevicePublicKeyBase64, activationName, extras, applicationKey, applicationSignature, keyConversionUtilities);
            logger.info("PrepareActivationRequest succeeded");
            return response;
        } catch (IllegalArgumentException ex) {
            logger.error("Unknown error occurred", ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_INPUT_FORMAT);
        } catch (GenericServiceException ex) {
            logger.error("Unknown error occurred", ex);
            throw ex;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public CreateActivationResponse createActivation(CreateActivationRequest request) throws Exception {
        try {
            // Get request parameters
            String applicationKey = request.getApplicationKey();
            String userId = request.getUserId();
            Long maxFailedCount = request.getMaxFailureCount();
            Date activationExpireTimestamp = XMLGregorianCalendarConverter.convertTo(request.getTimestampActivationExpire());
            String identity = request.getIdentity();
            String activationOtp = request.getActivationOtp();
            String activationNonceBase64 = request.getActivationNonce();
            String cDevicePublicKeyBase64 = request.getEncryptedDevicePublicKey();
            String activationName = request.getActivationName();
            String ephemeralPublicKey = request.getEphemeralPublicKey();
            String applicationSignature = request.getApplicationSignature();
            String extras = request.getExtras();
            logger.info("CreateActivationRequest received, userId: {}", userId);
            CreateActivationResponse response = behavior.getActivationServiceBehavior().createActivation(
                    applicationKey,
                    userId,
                    maxFailedCount,
                    activationExpireTimestamp,
                    identity,
                    activationOtp,
                    activationNonceBase64,
                    ephemeralPublicKey,
                    cDevicePublicKeyBase64,
                    activationName,
                    extras,
                    applicationSignature,
                    keyConversionUtilities
            );
            logger.info("CreateActivationRequest succeeded");
            return response;
        } catch (IllegalArgumentException ex) {
            logger.error("Unknown error occurred", ex);
            throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_INPUT_FORMAT);
        } catch (GenericServiceException ex) {
            logger.error("Unknown error occurred", ex);
            throw ex;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    private VerifySignatureResponse verifySignatureImplNonTransaction(VerifySignatureRequest request, KeyValueMap additionalInfo) throws Exception {

        // Get request data
        String activationId = request.getActivationId();
        String applicationKey = request.getApplicationKey();
        String dataString = request.getData();
        String signature = request.getSignature();
        SignatureType signatureType = request.getSignatureType();

        return behavior.getSignatureServiceBehavior().verifySignature(activationId, signatureType, signature, additionalInfo, dataString, applicationKey, keyConversionUtilities);

    }

    @Override
    @Transactional
    public VerifySignatureResponse verifySignature(VerifySignatureRequest request) throws Exception {
        try {
            logger.info("VerifySignatureRequest received, activationId: {}", request.getActivationId());
            VerifySignatureResponse response = this.verifySignatureImplNonTransaction(request, null);
            logger.info("VerifySignatureRequest succeeded");
            return response;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public CreatePersonalizedOfflineSignaturePayloadResponse createPersonalizedOfflineSignaturePayload(CreatePersonalizedOfflineSignaturePayloadRequest request) throws Exception {
        try {
            String activationId = request.getActivationId();
            String data = request.getData();
            logger.info("CreatePersonalizedOfflineSignaturePayloadRequest received, activationId: {}", activationId);
            CreatePersonalizedOfflineSignaturePayloadResponse response = behavior.getSignatureServiceBehavior().createPersonalizedOfflineSignaturePayload(activationId, data, keyConversionUtilities);
            logger.info("CreatePersonalizedOfflineSignaturePayloadRequest succeeded");
            return response;
        } catch (GenericServiceException ex) {
            logger.error("Unknown error occurred", ex);
            throw ex;
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            throw new GenericServiceException(ServiceError.UNABLE_TO_COMPUTE_SIGNATURE, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public CreateNonPersonalizedOfflineSignaturePayloadResponse createNonPersonalizedOfflineSignaturePayload(CreateNonPersonalizedOfflineSignaturePayloadRequest request) throws Exception {
        try {
            long applicationId = request.getApplicationId();
            String data = request.getData();
            logger.info("CreateNonPersonalizedOfflineSignaturePayloadRequest received, applicationId: {}", String.valueOf(applicationId));
            CreateNonPersonalizedOfflineSignaturePayloadResponse response = behavior.getSignatureServiceBehavior().createNonPersonalizedOfflineSignaturePayload(applicationId, data, keyConversionUtilities);
            logger.info("CreateNonPersonalizedOfflineSignaturePayloadRequest succeeded");
            return response;
        } catch (GenericServiceException ex) {
            logger.error("Unknown error occurred", ex);
            throw ex;
        } catch (Exception ex) {
            logger.error(ex.getMessage(), ex);
            throw new GenericServiceException(ServiceError.UNABLE_TO_COMPUTE_SIGNATURE, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public VerifyOfflineSignatureResponse verifyOfflineSignature(VerifyOfflineSignatureRequest request) throws Exception {
        final String activationId = request.getActivationId();
        final String data = request.getData();
        final String signature = request.getSignature();
        final SignatureType signatureType = request.getSignatureType();
        logger.info("VerifyOfflineSignatureRequest received, activationId: {}", activationId);
        VerifyOfflineSignatureResponse response = behavior.getSignatureServiceBehavior().verifyOfflineSignature(activationId, signatureType, signature, data, keyConversionUtilities);
        logger.info("VerifyOfflineSignatureRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public CommitActivationResponse commitActivation(CommitActivationRequest request) throws Exception {
        try {
            String activationId = request.getActivationId();
            logger.info("CommitActivationRequest received, activationId: {}", activationId);
            CommitActivationResponse response = behavior.getActivationServiceBehavior().commitActivation(activationId);
            logger.info("CommitActivationRequest succeeded", request.getActivationId());
            return response;
        } catch (GenericServiceException ex) {
            logger.error("Unknown error occurred", ex);
            throw ex;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public RemoveActivationResponse removeActivation(RemoveActivationRequest request) throws Exception {
        try {
            String activationId = request.getActivationId();
            logger.info("RemoveActivationRequest received, activationId: {}", activationId);
            RemoveActivationResponse response = behavior.getActivationServiceBehavior().removeActivation(activationId);
            logger.info("RemoveActivationRequest succeeded");
            return response;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public BlockActivationResponse blockActivation(BlockActivationRequest request) throws Exception {
        try {
            String activationId = request.getActivationId();
            String reason = request.getReason();
            logger.info("BlockActivationRequest received, activationId: {}", activationId);
            BlockActivationResponse response = behavior.getActivationServiceBehavior().blockActivation(activationId, reason);
            logger.info("BlockActivationRequest succeeded");
            return response;
        } catch (GenericServiceException ex) {
            logger.error("Unknown error occurred", ex);
            throw ex;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public UnblockActivationResponse unblockActivation(UnblockActivationRequest request) throws Exception {
        try {
            String activationId = request.getActivationId();
            logger.info("UnblockActivationRequest received, activationId: {}", activationId);
            UnblockActivationResponse response = behavior.getActivationServiceBehavior().unblockActivation(activationId);
            logger.info("UnblockActivationRequest succeeded");
            return response;
        } catch (GenericServiceException ex) {
            logger.error("Unknown error occurred", ex);
            throw ex;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }

    }

    @Override
    @Transactional
    public VaultUnlockResponse vaultUnlock(VaultUnlockRequest request) throws Exception {
        try {

            // Get request data
            String activationId = request.getActivationId();
            String applicationKey = request.getApplicationKey();
            String signature = request.getSignature();
            SignatureType signatureType = request.getSignatureType();
            String data = request.getData();
            String reason = request.getReason();

            logger.info("VaultUnlockRequest received, activationId: {}", activationId);

            // Reject 1FA signatures.
            if (signatureType.equals(SignatureType.BIOMETRY)
                    || signatureType.equals(SignatureType.KNOWLEDGE)
                    || signatureType.equals(SignatureType.POSSESSION)) {
                throw localizationProvider.buildExceptionForCode(ServiceError.INVALID_SIGNATURE);
            }

            // Save vault unlock reason into additional info which is logged in signature audit log.
            // If value unlock reason is missing, use default NOT_SPECIFIED value.
            KeyValueMap additionalInfo = new KeyValueMap();
            KeyValueMap.Entry entry = new KeyValueMap.Entry();
            entry.setKey(AdditionalInformation.VAULT_UNLOCKED_REASON);
            if (reason == null) {
                entry.setValue(AdditionalInformation.VAULT_UNLOCKED_REASON_NOT_SPECIFIED);
            } else {
                entry.setValue(reason);
            }
            additionalInfo.getEntry().add(entry);

            // Verify the signature
            VerifySignatureRequest verifySignatureRequest = new VerifySignatureRequest();
            verifySignatureRequest.setActivationId(activationId);
            verifySignatureRequest.setApplicationKey(applicationKey);
            verifySignatureRequest.setData(data);
            verifySignatureRequest.setSignature(signature);
            verifySignatureRequest.setSignatureType(signatureType);
            VerifySignatureResponse verifySignatureResponse = this.verifySignatureImplNonTransaction(verifySignatureRequest, additionalInfo);

            VaultUnlockResponse response = behavior.getVaultUnlockServiceBehavior().unlockVault(activationId, verifySignatureResponse.isSignatureValid(), keyConversionUtilities);
            logger.info("VaultUnlockRequest succeeded");
            return response;
        } catch (GenericServiceException ex) {
            logger.error("Unknown error occurred", ex);
            throw ex;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public GetPersonalizedEncryptionKeyResponse generateE2EPersonalizedEncryptionKey(GetPersonalizedEncryptionKeyRequest request) throws Exception {
        logger.info("GetPersonalizedEncryptionKeyRequest received, activationId: {}", request.getActivationId());
        GetPersonalizedEncryptionKeyResponse response = behavior.getEncryptionServiceBehavior().generateEncryptionKeyForActivation(
                request.getActivationId(),
                request.getSessionIndex(),
                keyConversionUtilities
        );
        logger.info("GetPersonalizedEncryptionKeyRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public GetNonPersonalizedEncryptionKeyResponse generateE2ENonPersonalizedEncryptionKey(GetNonPersonalizedEncryptionKeyRequest request) throws Exception {
        logger.info("GetNonPersonalizedEncryptionKeyRequest received");
        GetNonPersonalizedEncryptionKeyResponse response = behavior.getEncryptionServiceBehavior().generateNonPersonalizedEncryptionKeyForApplication(
                request.getApplicationKey(),
                request.getSessionIndex(),
                request.getEphemeralPublicKey(),
                keyConversionUtilities
        );
        logger.info("GetNonPersonalizedEncryptionKeyRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public VerifyECDSASignatureResponse verifyECDSASignature(VerifyECDSASignatureRequest request) throws Exception {
        try {
            String activationId = request.getActivationId();
            String signedData = request.getData();
            String signature  = request.getSignature();
            logger.info("VerifyECDSASignatureRequest received, activationId: {}", activationId);
            boolean matches = behavior.getAsymmetricSignatureServiceBehavior().verifyECDSASignature(activationId, signedData, signature, keyConversionUtilities);
            VerifyECDSASignatureResponse response = new VerifyECDSASignatureResponse();
            response.setSignatureValid(matches);
            logger.info("VerifyECDSASignatureRequest succeeded");
            return response;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public SignatureAuditResponse getSignatureAuditLog(SignatureAuditRequest request) throws Exception {
        try {

            String userId = request.getUserId();
            Long applicationId = request.getApplicationId();
            Date startingDate = XMLGregorianCalendarConverter.convertTo(request.getTimestampFrom());
            Date endingDate = XMLGregorianCalendarConverter.convertTo(request.getTimestampTo());

            logger.info("SignatureAuditRequest received, userId: {}, applicationId: {}", userId, String.valueOf(applicationId));
            SignatureAuditResponse response = behavior.getAuditingServiceBehavior().getSignatureAuditLog(userId, applicationId, startingDate, endingDate);
            logger.info("SignatureAuditRequest succeeded");
            return response;

        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }

    }

    @Override
    public ActivationHistoryResponse getActivationHistory(ActivationHistoryRequest request) throws Exception {
        try {
            String activationId = request.getActivationId();
            Date startingDate = XMLGregorianCalendarConverter.convertTo(request.getTimestampFrom());
            Date endingDate = XMLGregorianCalendarConverter.convertTo(request.getTimestampTo());
            logger.info("ActivationHistoryRequest received, activationId: {}", activationId);
            ActivationHistoryResponse response = behavior.getActivationHistoryServiceBehavior().getActivationHistory(activationId, startingDate, endingDate);
            logger.info("ActivationHistoryRequest succeeded");
            return response;
        } catch (Exception ex) {
            logger.error("Unknown error occurred", ex);
            throw new GenericServiceException(ServiceError.UNKNOWN_ERROR, ex.getMessage(), ex.getLocalizedMessage());
        }
    }

    @Override
    @Transactional
    public GetApplicationListResponse getApplicationList(GetApplicationListRequest request) {
        logger.info("GetApplicationListRequest received");
        GetApplicationListResponse response = behavior.getApplicationServiceBehavior().getApplicationList();
        logger.info("GetApplicationListRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public GetApplicationDetailResponse getApplicationDetail(GetApplicationDetailRequest request) throws Exception {
        logger.info("GetApplicationDetailRequest received, applicationId: {}", String.valueOf(request.getApplicationId()));
        GetApplicationDetailResponse response = behavior.getApplicationServiceBehavior().getApplicationDetail(request.getApplicationId());
        logger.info("GetApplicationDetailRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public LookupApplicationByAppKeyResponse lookupApplicationByAppKey(LookupApplicationByAppKeyRequest request) throws Exception {
        try {
            logger.info("LookupApplicationByAppKeyRequest received");
            LookupApplicationByAppKeyResponse response = behavior.getApplicationServiceBehavior().lookupApplicationByAppKey(request.getApplicationKey());
            logger.info("LookupApplicationByAppKeyRequest succeeded");
            return response;
        } catch (Throwable t) {
            throw localizationProvider.buildExceptionForCode(ServiceError.NO_APPLICATION_ID);
        }
    }

    @Override
    @Transactional
    public CreateApplicationResponse createApplication(CreateApplicationRequest request) {
        logger.info("CreateApplicationRequest received, applicationName: {}", request.getApplicationName());
        CreateApplicationResponse response = behavior.getApplicationServiceBehavior().createApplication(request.getApplicationName(), keyConversionUtilities);
        logger.info("CreateApplicationRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public CreateApplicationVersionResponse createApplicationVersion(CreateApplicationVersionRequest request) throws Exception {
        logger.info("CreateApplicationVersionRequest received, applicationId: {}, applicationVersionName: {}", String.valueOf(request.getApplicationId()), request.getApplicationVersionName());
        CreateApplicationVersionResponse response = behavior.getApplicationServiceBehavior().createApplicationVersion(request.getApplicationId(), request.getApplicationVersionName());
        logger.info("CreateApplicationVersionRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public UnsupportApplicationVersionResponse unsupportApplicationVersion(UnsupportApplicationVersionRequest request) throws Exception {
        logger.info("UnsupportApplicationVersionRequest received, applicationVersionId: {}", request.getApplicationVersionId());
        UnsupportApplicationVersionResponse response = behavior.getApplicationServiceBehavior().unsupportApplicationVersion(request.getApplicationVersionId());
        logger.info("UnsupportApplicationVersionRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public SupportApplicationVersionResponse supportApplicationVersion(SupportApplicationVersionRequest request) throws Exception {
        logger.info("SupportApplicationVersionRequest received, applicationVersionId: {}", request.getApplicationVersionId());
        SupportApplicationVersionResponse response = behavior.getApplicationServiceBehavior().supportApplicationVersion(request.getApplicationVersionId());
        logger.info("SupportApplicationVersionRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public CreateIntegrationResponse createIntegration(CreateIntegrationRequest request) {
        logger.info("CreateIntegrationRequest received, name: {}", request.getName());
        CreateIntegrationResponse response = behavior.getIntegrationBehavior().createIntegration(request);
        logger.info("CreateIntegrationRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public GetIntegrationListResponse getIntegrationList() {
        logger.info("GetIntegrationListRequest received");
        GetIntegrationListResponse response = behavior.getIntegrationBehavior().getIntegrationList();
        logger.info("GetIntegrationListRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public RemoveIntegrationResponse removeIntegration(RemoveIntegrationRequest request) {
        logger.info("RemoveIntegrationRequest received, id: {}", request.getId());
        RemoveIntegrationResponse response = behavior.getIntegrationBehavior().removeIntegration(request);
        logger.info("RemoveIntegrationRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public CreateCallbackUrlResponse createCallbackUrl(CreateCallbackUrlRequest request) throws Exception {
        logger.info("CreateCallbackUrlRequest received, name: {}", request.getName());
        CreateCallbackUrlResponse response = behavior.getCallbackUrlBehavior().createCallbackUrl(request);
        logger.info("CreateCallbackUrlRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public GetCallbackUrlListResponse getCallbackUrlList(GetCallbackUrlListRequest request) {
        logger.info("GetCallbackUrlListRequest received, applicationId: {}", String.valueOf(request.getApplicationId()));
        GetCallbackUrlListResponse response = behavior.getCallbackUrlBehavior().getCallbackUrlList(request);
        logger.info("GetCallbackUrlListRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public RemoveCallbackUrlResponse removeCallbackUrl(RemoveCallbackUrlRequest request) {
        logger.info("RemoveCallbackUrlRequest received, id: {}", request.getId());
        RemoveCallbackUrlResponse response = behavior.getCallbackUrlBehavior().removeIntegration(request);
        logger.info("RemoveCallbackUrlRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public CreateTokenResponse createToken(CreateTokenRequest request) throws Exception {
        logger.info("CreateTokenRequest received, activationId: {}", request.getActivationId());
        CreateTokenResponse response = behavior.getTokenBehavior().createToken(request, keyConversionUtilities);
        logger.info("CreateTokenRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public ValidateTokenResponse validateToken(ValidateTokenRequest request) throws Exception {
        logger.info("ValidateTokenRequest received, tokenId: {}", request.getTokenId());
        ValidateTokenResponse response = behavior.getTokenBehavior().validateToken(request);
        logger.info("ValidateTokenRequest succeeded");
        return response;
    }

    @Override
    @Transactional
    public RemoveTokenResponse removeToken(RemoveTokenRequest request) {
        logger.info("RemoveTokenRequest received, tokenId: {}", request.getTokenId());
        RemoveTokenResponse response = behavior.getTokenBehavior().removeToken(request);
        logger.info("RemoveTokenRequest succeeded");
        return response;
    }

}
