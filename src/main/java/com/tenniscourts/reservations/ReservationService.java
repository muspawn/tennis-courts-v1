package com.tenniscourts.reservations;

import com.tenniscourts.exceptions.EntityNotFoundException;
import com.tenniscourts.guests.Guest;
import com.tenniscourts.schedules.Schedule;
import com.tenniscourts.schedules.ScheduleDTO;
import com.tenniscourts.schedules.ScheduleService;
import com.tenniscourts.tenniscourts.TennisCourt;
import com.tenniscourts.tenniscourts.TennisCourtDTO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@AllArgsConstructor
public class ReservationService {

    private final ScheduleService scheduleService;
    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;

    public ReservationDTO bookReservation(CreateReservationRequestDTO createReservationRequestDTO) {

           ScheduleDTO scheduleDTO = scheduleService.findSchedule(createReservationRequestDTO.getScheduleId());
           TennisCourtDTO tennisCourtDTO = scheduleDTO.getTennisCourt();

           TennisCourt tennisCourt = new TennisCourt();
           tennisCourt.setName(tennisCourtDTO.getName());
           tennisCourt.setId(tennisCourtDTO.getId());

           Guest guest = new Guest();
           guest.setId(createReservationRequestDTO.getGuestId());
           List<Reservation> rs = reservationRepository.findBySchedule_Id(scheduleDTO.getId());

           Schedule schedule = Schedule.builder()
                   .endDateTime(scheduleDTO.getEndDateTime())
                   .startDateTime(scheduleDTO.getStartDateTime())
                   .tennisCourt(tennisCourt)
                   .reservations(rs)
                   .build();

           Reservation reservation = Reservation.builder()
                   .guest(guest)
                   .reservationStatus(ReservationStatus.READY_TO_PLAY )
                   .schedule(schedule)
                   .refundValue(new BigDecimal(10))
                   .build();

          reservationRepository.save(reservation);

           ReservationDTO reservationDTO = ReservationDTO.builder()
                   .reservationStatus(reservation.getReservationStatus().toString())
                   .guestId(reservation.getGuest().getId())
                   .schedule(scheduleDTO)
                   .scheduledId(scheduleDTO.getId())
                   .build();

             return  reservationDTO;

    }

    public ReservationDTO findReservation(Long reservationId) {
        return reservationRepository.findById(reservationId).map(reservationMapper::map).orElseThrow(() -> {
            throw new EntityNotFoundException("Reservation not found.");
        });
    }

    public ReservationDTO cancelReservation(Long reservationId) {
        return reservationMapper.map(this.cancel(reservationId));
    }

    private Reservation cancel(Long reservationId) {
        return reservationRepository.findById(reservationId).map(reservation -> {

            this.validateCancellation(reservation);

            BigDecimal refundValue = getRefundValue(reservation);
            return this.updateReservation(reservation, refundValue, ReservationStatus.CANCELLED);

        }).orElseThrow(() -> {
            throw new EntityNotFoundException("Reservation not found.");
        });
    }

    private Reservation updateReservation(Reservation reservation, BigDecimal refundValue, ReservationStatus status) {
        reservation.setReservationStatus(status);
        reservation.setValue(reservation.getValue().subtract(refundValue));
        reservation.setRefundValue(refundValue);

        return reservationRepository.save(reservation);
    }

    private void validateCancellation(Reservation reservation) {
        if (!ReservationStatus.READY_TO_PLAY.equals(reservation.getReservationStatus())) {
            throw new IllegalArgumentException("Cannot cancel/reschedule because it's not in ready to play status.");
        }

        if (reservation.getSchedule().getStartDateTime().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Can cancel/reschedule only future dates.");
        }
    }

    public BigDecimal getRefundValue(Reservation reservation) {
        long hours = ChronoUnit.HOURS.between(LocalDateTime.now(), reservation.getSchedule().getStartDateTime());

        if (hours >= 24) {
            return reservation.getValue();
        }

        return BigDecimal.ZERO;
    }


    public ReservationDTO rescheduleReservation(Long previousReservationId, Long scheduleId) {
        Reservation previousReservation = cancel(previousReservationId);

        if (scheduleId.equals(previousReservation.getSchedule().getId())) {
            throw new IllegalArgumentException("Cannot reschedule to the same slot.");
        }

        previousReservation.setReservationStatus(ReservationStatus.RESCHEDULED);
        reservationRepository.save(previousReservation);

        ReservationDTO newReservation = bookReservation(CreateReservationRequestDTO.builder()
                .guestId(previousReservation.getGuest().getId())
                .scheduleId(scheduleId)
                .build());
        newReservation.setPreviousReservation(reservationMapper.map(previousReservation));
        return newReservation;
    }
}
