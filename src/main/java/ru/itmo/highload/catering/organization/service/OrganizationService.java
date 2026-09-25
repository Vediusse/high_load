package ru.itmo.highload.catering.organization.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload.catering.common.dto.PageResponse;
import ru.itmo.highload.catering.common.error.ApiException;
import ru.itmo.highload.catering.organization.dto.ActiveOrderParty;
import ru.itmo.highload.catering.organization.dto.CreateDeliveryPointRequest;
import ru.itmo.highload.catering.organization.dto.CreateOrganizationRequest;
import ru.itmo.highload.catering.organization.dto.DeliveryPointResponse;
import ru.itmo.highload.catering.organization.dto.OrganizationPageResult;
import ru.itmo.highload.catering.organization.dto.OrganizationResponse;
import ru.itmo.highload.catering.organization.dto.UpdateDeliveryPointRequest;
import ru.itmo.highload.catering.organization.dto.UpdateOrganizationRequest;
import ru.itmo.highload.catering.organization.entity.DeliveryPoint;
import ru.itmo.highload.catering.organization.entity.Organization;
import ru.itmo.highload.catering.organization.repository.DeliveryPointRepository;
import ru.itmo.highload.catering.organization.repository.OrganizationRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final DeliveryPointRepository deliveryPointRepository;

    @Transactional
    public OrganizationResponse createOrganization(CreateOrganizationRequest request) {
        Organization organization = new Organization(request.name(), request.phone());
        return toResponse(organizationRepository.saveAndFlush(organization));
    }

    public OrganizationResponse getOrganization(UUID id) {
        return toResponse(requireOrganization(id));
    }

    @Transactional
    public OrganizationResponse updateOrganization(UUID id, UpdateOrganizationRequest request) {
        Organization organization = requireOrganization(id);
        organization.update(request.name(), request.phone());
        return toResponse(organizationRepository.saveAndFlush(organization));
    }

    @Transactional
    public void deactivateOrganization(UUID id) {
        Organization organization = requireOrganization(id);
        organization.deactivate();
        organizationRepository.flush();
    }

    public OrganizationPageResult listOrganizations(PageRequest pageRequest) {
        Page<Organization> page = organizationRepository.findAll(pageRequest);
        PageResponse<OrganizationResponse> body = new PageResponse<>(
                page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(),
                page.getSize(),
                page.hasNext());
        return new OrganizationPageResult(body, page.getTotalElements());
    }

    public ActiveOrderParty requireActiveOrganizationAndPoint(UUID organizationId, UUID deliveryPointId) {
        Organization organization = requireOrganization(organizationId);
        if (!organization.isActive()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "ORGANIZATION_INACTIVE",
                    "Нельзя использовать неактивную организацию в новом заказе");
        }
        DeliveryPoint deliveryPoint = requireDeliveryPoint(deliveryPointId);
        if (!deliveryPoint.getOrganization().getId().equals(organizationId)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DELIVERY_POINT_ORGANIZATION_MISMATCH",
                    "Точка выдачи не принадлежит организации заказа");
        }
        if (!deliveryPoint.isActive()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DELIVERY_POINT_INACTIVE",
                    "Нельзя использовать неактивную точку выдачи в новом заказе");
        }
        return new ActiveOrderParty(organizationId, deliveryPointId);
    }

    @Transactional
    public DeliveryPointResponse createDeliveryPoint(UUID organizationId, CreateDeliveryPointRequest request) {
        Organization organization = requireOrganization(organizationId);
        if (!organization.isActive()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "ORGANIZATION_INACTIVE",
                    "Нельзя добавить точку выдачи неактивной организации");
        }
        String normalizedName = request.name().trim();
        if (deliveryPointRepository.existsByOrganizationIdAndName(organizationId, normalizedName)) {
            throw deliveryPointNameConflict();
        }

        DeliveryPoint deliveryPoint = new DeliveryPoint(
                organization,
                normalizedName,
                request.address(),
                request.contactName(),
                request.contactPhone());
        organization.addDeliveryPoint(deliveryPoint);
        return toResponse(deliveryPointRepository.saveAndFlush(deliveryPoint));
    }

    public PageResponse<DeliveryPointResponse> listDeliveryPoints(UUID organizationId, PageRequest pageRequest) {
        requireOrganization(organizationId);
        Slice<DeliveryPoint> slice = deliveryPointRepository.findAllByOrganizationId(organizationId, pageRequest);
        return new PageResponse<>(
                slice.getContent().stream().map(this::toResponse).toList(),
                slice.getNumber(),
                slice.getSize(),
                slice.hasNext());
    }

    @Transactional
    public DeliveryPointResponse updateDeliveryPoint(UUID id, UpdateDeliveryPointRequest request) {
        DeliveryPoint deliveryPoint = requireDeliveryPoint(id);
        UUID organizationId = deliveryPoint.getOrganization().getId();
        String normalizedName = request.name().trim();
        if (deliveryPointRepository.existsByOrganizationIdAndNameAndIdNot(
                organizationId,
                normalizedName,
                id)) {
            throw deliveryPointNameConflict();
        }
        deliveryPoint.update(
                normalizedName,
                request.address(),
                request.contactName(),
                request.contactPhone());
        return toResponse(deliveryPointRepository.saveAndFlush(deliveryPoint));
    }

    @Transactional
    public void deactivateDeliveryPoint(UUID id) {
        DeliveryPoint deliveryPoint = requireDeliveryPoint(id);
        deliveryPoint.deactivate();
        deliveryPointRepository.flush();
    }

    private Organization requireOrganization(UUID id) {
        return organizationRepository.findById(id)
                .orElseThrow(() -> notFound("Организация", id));
    }

    private DeliveryPoint requireDeliveryPoint(UUID id) {
        return deliveryPointRepository.findById(id)
                .orElseThrow(() -> notFound("Точка выдачи", id));
    }

    private ApiException notFound(String resource, UUID id) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                resource + " с идентификатором " + id + " не найдена");
    }

    private ApiException deliveryPointNameConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "DELIVERY_POINT_NAME_CONFLICT",
                "Точка выдачи с таким названием уже существует в организации");
    }

    private OrganizationResponse toResponse(Organization organization) {
        return new OrganizationResponse(
                organization.getId(),
                organization.getName(),
                organization.getPhone(),
                organization.isActive(),
                organization.getVersion());
    }

    private DeliveryPointResponse toResponse(DeliveryPoint deliveryPoint) {
        return new DeliveryPointResponse(
                deliveryPoint.getId(),
                deliveryPoint.getOrganization().getId(),
                deliveryPoint.getName(),
                deliveryPoint.getAddress(),
                deliveryPoint.getContactName(),
                deliveryPoint.getContactPhone(),
                deliveryPoint.isActive());
    }
}
