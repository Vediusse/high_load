package ru.itmo.highload.catering.organization.application;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.itmo.highload.catering.organization.application.dto.in.CreateDeliveryPointRequest;
import ru.itmo.highload.catering.organization.application.dto.in.CreateOrganizationRequest;
import ru.itmo.highload.catering.organization.application.dto.in.UpdateDeliveryPointRequest;
import ru.itmo.highload.catering.organization.application.dto.in.UpdateOrganizationRequest;
import ru.itmo.highload.catering.organization.application.dto.out.ActiveOrderParty;
import ru.itmo.highload.catering.organization.application.dto.out.DeliveryPointResponse;
import ru.itmo.highload.catering.organization.application.dto.out.OrganizationPageResult;
import ru.itmo.highload.catering.organization.application.dto.out.OrganizationResponse;
import ru.itmo.highload.catering.organization.application.ports.DeliveryPointStore;
import ru.itmo.highload.catering.organization.application.ports.OrganizationStore;
import ru.itmo.highload.catering.organization.domain.model.DeliveryPoint;
import ru.itmo.highload.catering.organization.domain.model.Organization;
import ru.itmo.highload.common.domain.BusinessFailure;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.PageResult;
import ru.itmo.highload.common.dto.out.PageResponse;

@RequiredArgsConstructor
public class OrganizationService {

    private final OrganizationStore organizationRepository;
    private final DeliveryPointStore deliveryPointRepository;

    public OrganizationResponse createOrganization(CreateOrganizationRequest request) {
        Organization organization = new Organization(request.name(), request.phone());
        return toResponse(organizationRepository.saveAndFlush(organization));
    }

    public OrganizationResponse getOrganization(UUID id) {
        return getOrganization(id, null);
    }

    public OrganizationResponse getOrganization(UUID id, UUID representativeOrganizationId) {
        requireRepresentativeOrganization(id, representativeOrganizationId);
        return toResponse(requireOrganization(id));
    }

    public OrganizationResponse updateOrganization(
            UUID id, UpdateOrganizationRequest request, UUID representativeOrganizationId) {
        requireRepresentativeOrganization(id, representativeOrganizationId);
        Organization organization = requireOrganization(id);
        organization.update(request.name(), request.phone());
        return toResponse(organizationRepository.saveAndFlush(organization));
    }

    public void deactivateOrganization(UUID id) {
        deactivateOrganization(id, null);
    }

    public void deactivateOrganization(UUID id, UUID representativeOrganizationId) {
        requireRepresentativeOrganization(id, representativeOrganizationId);
        Organization organization = requireOrganization(id);
        organization.deactivate();
        organizationRepository.flush();
    }

    public OrganizationPageResult listOrganizations(
            PageQuery pageRequest, UUID representativeOrganizationId) {
        PageResult<Organization> page =
                representativeOrganizationId == null
                        ? organizationRepository.findAll(pageRequest.withSort("id ASC"))
                        : organizationRepository.findById(
                                representativeOrganizationId, pageRequest.withSort("id ASC"));
        PageResponse<OrganizationResponse> body =
                new PageResponse<>(
                        page.items().stream().map(this::toResponse).toList(),
                        page.page(),
                        page.size(),
                        page.hasNext());
        return new OrganizationPageResult(body, page.total());
    }

    public ActiveOrderParty requireActiveOrganizationAndPoint(
            UUID organizationId, UUID deliveryPointId) {
        Organization organization = requireOrganization(organizationId);
        if (!organization.isActive()) {
            throw new BusinessFailure(
                    BusinessFailure.Kind.REJECTED,
                    "ORGANIZATION_INACTIVE",
                    "Нельзя использовать неактивную организацию в новом заказе");
        }
        DeliveryPoint deliveryPoint = requireDeliveryPoint(deliveryPointId);
        if (!deliveryPoint.getOrganization().getId().equals(organizationId)) {
            throw new BusinessFailure(
                    BusinessFailure.Kind.REJECTED,
                    "DELIVERY_POINT_ORGANIZATION_MISMATCH",
                    "Точка выдачи не принадлежит организации заказа");
        }
        if (!deliveryPoint.isActive()) {
            throw new BusinessFailure(
                    BusinessFailure.Kind.REJECTED,
                    "DELIVERY_POINT_INACTIVE",
                    "Нельзя использовать неактивную точку выдачи в новом заказе");
        }
        return new ActiveOrderParty(organizationId, deliveryPointId);
    }

    public DeliveryPointResponse createDeliveryPoint(
            UUID organizationId, CreateDeliveryPointRequest request) {
        return createDeliveryPoint(organizationId, request, null);
    }

    public DeliveryPointResponse createDeliveryPoint(
            UUID organizationId,
            CreateDeliveryPointRequest request,
            UUID representativeOrganizationId) {
        requireRepresentativeOrganization(organizationId, representativeOrganizationId);
        Organization organization = requireOrganization(organizationId);
        if (!organization.isActive()) {
            throw new BusinessFailure(
                    BusinessFailure.Kind.REJECTED,
                    "ORGANIZATION_INACTIVE",
                    "Нельзя добавить точку выдачи неактивной организации");
        }
        String normalizedName = request.name().trim();
        if (deliveryPointRepository.existsByOrganizationIdAndName(organizationId, normalizedName)) {
            throw deliveryPointNameConflict();
        }

        DeliveryPoint deliveryPoint =
                new DeliveryPoint(
                        organization,
                        normalizedName,
                        request.address(),
                        request.contactName(),
                        request.contactPhone());
        organization.addDeliveryPoint(deliveryPoint);
        return toResponse(deliveryPointRepository.saveAndFlush(deliveryPoint));
    }

    public PageResponse<DeliveryPointResponse> listDeliveryPoints(
            UUID organizationId, PageQuery pageRequest, UUID representativeOrganizationId) {
        requireRepresentativeOrganization(organizationId, representativeOrganizationId);
        requireOrganization(organizationId);
        PageResult<DeliveryPoint> slice =
                deliveryPointRepository.findAllByOrganizationId(
                        organizationId, pageRequest.withSort("id ASC"));
        return new PageResponse<>(
                slice.items().stream().map(this::toResponse).toList(),
                slice.page(),
                slice.size(),
                slice.hasNext());
    }

    public DeliveryPointResponse updateDeliveryPoint(
            UUID id, UpdateDeliveryPointRequest request, UUID representativeOrganizationId) {
        DeliveryPoint deliveryPoint = requireDeliveryPoint(id);
        UUID organizationId = deliveryPoint.getOrganization().getId();
        requireRepresentativeOrganization(organizationId, representativeOrganizationId);
        String normalizedName = request.name().trim();
        if (deliveryPointRepository.existsByOrganizationIdAndNameAndIdNot(
                organizationId, normalizedName, id)) {
            throw deliveryPointNameConflict();
        }
        deliveryPoint.update(
                normalizedName, request.address(), request.contactName(), request.contactPhone());
        return toResponse(deliveryPointRepository.saveAndFlush(deliveryPoint));
    }

    public void deactivateDeliveryPoint(UUID id) {
        deactivateDeliveryPoint(id, null);
    }

    public void deactivateDeliveryPoint(UUID id, UUID representativeOrganizationId) {
        DeliveryPoint deliveryPoint = requireDeliveryPoint(id);
        requireRepresentativeOrganization(
                deliveryPoint.getOrganization().getId(), representativeOrganizationId);
        deliveryPoint.deactivate();
        deliveryPointRepository.flush();
    }

    private Organization requireOrganization(UUID id) {
        return organizationRepository.findById(id).orElseThrow(() -> notFound("Организация", id));
    }

    private void requireRepresentativeOrganization(UUID id, UUID representativeOrganizationId) {
        if (representativeOrganizationId != null && !representativeOrganizationId.equals(id)) {
            throw notFound("Организация", id);
        }
    }

    private DeliveryPoint requireDeliveryPoint(UUID id) {
        return deliveryPointRepository.findById(id).orElseThrow(() -> notFound("Точка выдачи", id));
    }

    private BusinessFailure notFound(String resource, UUID id) {
        return new BusinessFailure(
                BusinessFailure.Kind.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                resource + " с идентификатором " + id + " не найдена");
    }

    private BusinessFailure deliveryPointNameConflict() {
        return new BusinessFailure(
                BusinessFailure.Kind.CONFLICT,
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
