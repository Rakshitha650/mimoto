package io.mosip.mimoto.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.JsonUtils;
import io.mosip.mimoto.constant.ApiName;
import io.mosip.mimoto.core.http.ResponseWrapper;
import io.mosip.mimoto.dto.ErrorDTO;
import io.mosip.mimoto.dto.idp.TokenResponseDTO;
import io.mosip.mimoto.dto.mimoto.*;
import io.mosip.mimoto.exception.IdpException;
import io.mosip.mimoto.exception.PlatformErrorMessages;
import io.mosip.mimoto.service.IdpService;
import io.mosip.mimoto.service.RestClientService;
import io.mosip.mimoto.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
public class IdpController {

    private final Logger logger = LoggerUtil.getLogger(IdpController.class);
    private static final boolean useBearerToken = true;
    private static final String ID = "mosip.mimoto.idp";
    private Gson gson = new Gson();

    @Autowired
    private RestClientService<Object> restClientService;

    @Autowired
    private JoseUtil joseUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    RequestValidator requestValidator;

    @Value("${mosip.oidc.esignet.token.endpoint}")
    String getTokenEndpoint;

    @Autowired
    IdpService idpService;

    @PostMapping("/binding-otp")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Object> otpRequest(@Valid @RequestBody BindingOtpRequestDto requestDTO, BindingResult result) throws Exception {
        logger.debug("Received binding-otp request : " + JsonUtils.javaObjectToJsonString(requestDTO));
        requestValidator.validateInputRequest(result);
        requestValidator.validateNotificationChannel(requestDTO.getRequest().getOtpChannels());
        ResponseWrapper<BindingOtpResponseDto> response = null;
        try {
            response = (ResponseWrapper<BindingOtpResponseDto>) restClientService
                    .postApi(ApiName.BINDING_OTP,
                            requestDTO, ResponseWrapper.class, useBearerToken);
            if (response == null)
                throw new IdpException();

        } catch (Exception e) {
            logger.error("Wallet binding otp error occured.", e);
            response = getErrorResponse(PlatformErrorMessages.MIMOTO_OTP_BINDING_EXCEPTION.getCode(), e.getMessage());
        }
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PostMapping(path = "/wallet-binding", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> request(@RequestBody WalletBindingRequestDTO requestDTO)
            throws Exception {

        logger.debug("Received wallet-binding request : " + JsonUtils.javaObjectToJsonString(requestDTO));

        ResponseWrapper<WalletBindingResponseDto> response = null;
        try {
            WalletBindingInnerRequestDto innerRequestDto = new WalletBindingInnerRequestDto();
            innerRequestDto.setChallengeList(requestDTO.getRequest().getChallengeList());
            innerRequestDto.setIndividualId(requestDTO.getRequest().getIndividualId());
            innerRequestDto.setPublicKey(JoseUtil.getJwkFromPublicKey(requestDTO.getRequest().getPublicKey()));
            innerRequestDto.setAuthFactorType(requestDTO.getRequest().getAuthFactorType());
            innerRequestDto.setFormat(requestDTO.getRequest().getFormat());

            WalletBindingInternalRequestDTO req = new WalletBindingInternalRequestDTO(requestDTO.getRequestTime(), innerRequestDto);

            ResponseWrapper<WalletBindingInternalResponseDto> internalResponse = (ResponseWrapper<WalletBindingInternalResponseDto>) restClientService
                    .postApi(ApiName.WALLET_BINDING,
                            req, ResponseWrapper.class, useBearerToken);

            if (internalResponse == null)
                throw new IdpException();

            response = joseUtil.addThumbprintAndKeyId(internalResponse);

        } catch (Exception e) {
            logger.error("Wallet binding error occured for tranaction id " + requestDTO.getRequest().getIndividualId(), e);
            response = getErrorResponse(PlatformErrorMessages.MIMOTO_WALLET_BINDING_EXCEPTION.getCode(), e.getMessage());
        }

        return ResponseEntity.status(HttpStatus.OK).body(response);
    }

    @PostMapping(value = "/get-token", consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE})
    public ResponseEntity getToken(@RequestParam Map<String, String> params) {
        logger.info("Started Token Call get-token-> " + params.toString());
        RestTemplate restTemplate = new RestTemplate();
        try {
            HttpEntity<MultiValueMap<String, String>> request = idpService.constructGetTokenRequest(params);
            TokenResponseDTO response = restTemplate.postForObject(getTokenEndpoint, request, TokenResponseDTO.class);
            return ResponseEntity.status(HttpStatus.OK).body(response);
        } catch (Exception ex){
            logger.error("Exception Occured while invoking the get-token endpoint", ex);
            ResponseWrapper response = getErrorResponse(PlatformErrorMessages.MIMOTO_IDP_GENERIC_EXCEPTION.getCode(), ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    private ResponseWrapper getErrorResponse(String errorCode, String errorMessage) {

        List<ErrorDTO> errors = getErrors(errorCode, errorMessage);
        ResponseWrapper responseWrapper = new ResponseWrapper();
        responseWrapper.setResponse(null);
        responseWrapper.setResponsetime(DateUtils.getRequestTimeString());
        responseWrapper.setId(ID);
        responseWrapper.setErrors(errors);

        return responseWrapper;
    }

    private List<ErrorDTO> getErrors(String errorCode, String errorMessage) {
        ErrorDTO errorDTO = new ErrorDTO(errorCode, errorMessage);
        return Lists.newArrayList(errorDTO);
    }
}
