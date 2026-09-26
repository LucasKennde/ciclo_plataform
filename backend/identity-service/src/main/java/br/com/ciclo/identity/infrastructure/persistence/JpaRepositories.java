package br.com.ciclo.identity.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface UserJpaRepository
    extends JpaRepository<UserEntity, UUID>, JpaSpecificationExecutor<UserEntity> {
  Optional<UserEntity> findByEmail(String email);
}

interface WorkspaceJpaRepository extends JpaRepository<WorkspaceEntity, UUID> {
  Optional<WorkspaceEntity> findFirstByOwnerId(UUID ownerId);
}

interface SessionJpaRepository extends JpaRepository<RefreshSessionEntity, UUID> {
  Optional<RefreshSessionEntity> findByTokenHash(String hash);
}

interface AccountTokenJpaRepository extends JpaRepository<AccountTokenEntity, UUID> {
  Optional<AccountTokenEntity> findByTokenHashAndKind(String hash, String kind);

  List<AccountTokenEntity> findAllByUserIdAndKindAndConsumedAtIsNull(UUID userId, String kind);
}

interface IdentitySettingsJpaRepository extends JpaRepository<IdentitySettingsEntity, Short> {}
