import React from 'react';
import { useStore } from '../../store';
import { StepReviewYesterday } from './StepReviewYesterday';
import { StepTriageInbox } from './StepTriageInbox';
import { StepTimebox } from './StepTimebox';
import {
    CheckCircle,
    Inbox,
    CalendarDays,
    X,
    ChevronRight,
    ChevronLeft
} from 'lucide-react';

export const PlanningFlowOverlay: React.FC = () => {
    const { planningFlow, closePlanningFlow, setPlanningStep } = useStore();

    if (!planningFlow.isOpen) return null;

    const steps = [
        { id: 0, title: 'Review Yesterday', icon: <CheckCircle className="w-5 h-5" /> },
        { id: 1, title: 'Triage Inbox', icon: <Inbox className="w-5 h-5" /> },
        { id: 2, title: 'Timebox Today', icon: <CalendarDays className="w-5 h-5" /> },
    ];

    const handleNext = () => {
        if (planningFlow.step < 2) {
            setPlanningStep((planningFlow.step + 1) as 1 | 2);
        } else {
            closePlanningFlow();
        }
    };

    const handlePrev = () => {
        if (planningFlow.step > 0) {
            setPlanningStep((planningFlow.step - 1) as 0 | 1);
        }
    };

    return (
        <div className="fixed inset-0 z-50 bg-surface-950/90 backdrop-blur-sm flex justify-center pt-16 px-4 pb-8 overflow-y-auto w-full h-full">
            <div className="w-full max-w-4xl bg-surface-900 border border-surface-800 rounded-xl shadow-2xl flex flex-col h-full max-h-[85vh] animate-scale-in relative">

                {/* Header */}
                <div className="flex items-center justify-between px-6 py-4 border-b border-surface-800 shrink-0">
                    <h2 className="text-xl font-semibold text-surface-100 flex items-center gap-2">
                        {steps[planningFlow.step].icon}
                        Plan Your Day
                    </h2>

                    {/* Stepper */}
                    <div className="flex gap-2">
                        {steps.map((s) => (
                            <div
                                key={s.id}
                                className={`h-2 rounded-full transition-all duration-300 ${s.id === planningFlow.step
                                    ? 'w-12 bg-accent-500'
                                    : s.id < planningFlow.step
                                        ? 'w-8 bg-accent-500/40'
                                        : 'w-8 bg-surface-800'
                                    }`}
                                title={s.title}
                            />
                        ))}
                    </div>

                    <button
                        onClick={closePlanningFlow}
                        className="p-2 -mr-2 text-surface-400 hover:text-surface-100 rounded-lg hover:bg-surface-800 transition-colors"
                    >
                        <X className="w-5 h-5" />
                    </button>
                </div>

                {/* Content Area */}
                <div className="flex-1 overflow-hidden flex flex-col min-h-0 bg-surface-950/50">
                    <div className="h-full overflow-y-auto p-6 custom-scrollbar">
                        {planningFlow.step === 0 && <StepReviewYesterday />}
                        {planningFlow.step === 1 && <StepTriageInbox />}
                        {planningFlow.step === 2 && <StepTimebox />}
                    </div>
                </div>

                {/* Footer Controls */}
                <div className="flex items-center justify-between px-6 py-4 border-t border-surface-800 bg-surface-900 shrink-0 rounded-b-xl">
                    <button
                        onClick={handlePrev}
                        disabled={planningFlow.step === 0}
                        className={`flex items-center gap-2 px-4 py-2 rounded-lg font-medium transition-all ${planningFlow.step === 0
                            ? 'opacity-0 pointer-events-none'
                            : 'text-surface-300 hover:text-surface-100 hover:bg-surface-800'
                            }`}
                    >
                        <ChevronLeft className="w-4 h-4" />
                        Previous
                    </button>

                    <button
                        onClick={handleNext}
                        className="flex items-center gap-2 px-5 py-2.5 bg-accent-600 hover:bg-accent-500 text-white rounded-lg font-medium transition-colors shadow-lg shadow-accent-500/20"
                    >
                        {planningFlow.step === 2 ? 'Finish Planning' : 'Next Step'}
                        {planningFlow.step < 2 && <ChevronRight className="w-4 h-4" />}
                    </button>
                </div>
            </div>
        </div>
    );
};
