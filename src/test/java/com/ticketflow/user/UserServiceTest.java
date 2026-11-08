package com.ticketflow.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.user.dto.UserCredentials;
import com.ticketflow.user.dto.UserResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    @Test
    void createCustomerNormalizesEmailAssignsCustomerRoleAndKeepsGivenHash() {
        when(userRepository.existsByEmail("ana@example.com")).thenReturn(false);
        when(userRepository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = userService.createCustomer("  Ana@Example.COM ", "HASH", " Ana ");

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("ana@example.com");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("HASH");
        assertThat(saved.getValue().isEnabled()).isTrue();
        assertThat(response.displayName()).isEqualTo("Ana");
    }

    @Test
    void createCustomerWithTakenEmailIsConflict() {
        when(userRepository.existsByEmail("ana@example.com")).thenReturn(true);

        assertThatThrownBy(() -> userService.createCustomer("ANA@example.com", "HASH", "Ana"))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void findCredentialsLooksUpByNormalizedEmail() {
        AppUser user = AppUser.newCustomer("ana@example.com", "HASH", "Ana");
        when(userRepository.findByEmail("ana@example.com")).thenReturn(Optional.of(user));

        Optional<UserCredentials> credentials = userService.findCredentialsByEmail(" ANA@example.com");

        assertThat(credentials).hasValueSatisfying(c -> {
            assertThat(c.passwordHash()).isEqualTo("HASH");
            assertThat(c.role()).isEqualTo(Role.CUSTOMER);
        });
    }

    @Test
    void findByIdOfMissingUserIsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findById(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void customerCanBeGrantedOrganizer() {
        AppUser user = AppUser.newCustomer("ana@example.com", "HASH", "Ana");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThat(userService.changeRole(1L, Role.ORGANIZER).role()).isEqualTo(Role.ORGANIZER);
    }

    @Test
    void adminRoleCannotBeGrantedNorRevoked() {
        AppUser customer = AppUser.newCustomer("ana@example.com", "HASH", "Ana");
        AppUser admin = AppUser.newAdmin("root@example.com", "HASH", "Root");
        when(userRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(userRepository.findById(2L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> userService.changeRole(1L, Role.ADMIN)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> userService.changeRole(2L, Role.CUSTOMER)).isInstanceOf(BusinessRuleException.class);
    }
}
