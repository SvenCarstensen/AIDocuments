package de.aidocuments.reminder;

import de.aidocuments.reminder.ReminderService.DismissRequest;
import de.aidocuments.reminder.ReminderService.DocumentReminders;
import de.aidocuments.reminder.ReminderService.Overview;
import de.aidocuments.reminder.ReminderService.Person;
import de.aidocuments.reminder.ReminderService.ReminderDto;
import de.aidocuments.reminder.ReminderService.ReminderRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Wiedervorlage – für alle Angemeldeten; was jemand sieht, bestimmen die Bereiche der Dokumente. */
@RestController
@RequestMapping("/api")
public class ReminderController {

    public record CountDto(long due) {
    }

    private final ReminderService service;

    public ReminderController(ReminderService service) {
        this.service = service;
    }

    @GetMapping("/reminders")
    public Overview overview(@RequestParam(defaultValue = "true") boolean mine) {
        return service.overview(mine);
    }

    @GetMapping("/reminders/count")
    public CountDto count() {
        return new CountDto(service.dueCount());
    }

    @PutMapping("/reminders/{id}")
    public ReminderDto update(@PathVariable String id, @Valid @RequestBody ReminderRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/reminders/{id}/done")
    public ReminderDto done(@PathVariable String id) {
        return service.setDone(id, true);
    }

    @PostMapping("/reminders/{id}/reopen")
    public ReminderDto reopen(@PathVariable String id) {
        return service.setDone(id, false);
    }

    @DeleteMapping("/reminders/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    @GetMapping("/documents/{id}/reminders")
    public DocumentReminders forDocument(@PathVariable String id) {
        return service.forDocument(id);
    }

    @PostMapping("/documents/{id}/reminders")
    @ResponseStatus(HttpStatus.CREATED)
    public ReminderDto create(@PathVariable String id, @Valid @RequestBody ReminderRequest request) {
        return service.create(id, request);
    }

    @PostMapping("/documents/{id}/reminders/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable String id, @Valid @RequestBody DismissRequest request) {
        service.dismiss(id, request);
    }

    @GetMapping("/documents/{id}/reminders/assignees")
    public List<Person> assignees(@PathVariable String id) {
        return service.assignees(id);
    }
}
